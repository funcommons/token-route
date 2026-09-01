package fun.commons.tokenroute.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * report 批量器（01 §7.2 SDK 薄件主体之一）：缓冲 100 条 / 1s 定时双触发批量上报；
 * <b>补发纪律（02 §6）</b>：10700 部分成功后<b>只补发 rejected</b>——整批重发会使 accepted 部分
 * win/fail 双计数，污染状态机判定。
 * 泛型 = 上报条目类型，reject 判定由调用方注入（服务端/SDK 各自实现 sender）。
 */
public class TrReportBatcher<T> {

    /** 批量 sender：返回本批中被拒收的条目（10700.rejected），受理返回空列表 */
    public interface Sender<T> {
        List<T> send(List<T> batch);
    }

    private final int maxBatch;
    private final long flushIntervalMs;
    private final Sender<T> sender;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();
    private List<T> buffer = new ArrayList<>();
    private volatile boolean closed;

    public TrReportBatcher(int maxBatch, long flushIntervalMs, Sender<T> sender) {
        this.maxBatch = maxBatch;
        this.flushIntervalMs = flushIntervalMs;
        this.sender = sender;
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tr-report-batcher-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        this.scheduler.scheduleWithFixedDelay(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** 缓冲一条；缓冲满即整批发送（rejected 自动回炉） */
    public void add(T item) {
        List<T> batch = null;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("batcher 已关闭");
            }
            buffer.add(item);
            if (buffer.size() >= maxBatch) {
                batch = buffer;
                buffer = new ArrayList<>();
            }
        }
        if (batch != null) {
            sendWithResubmit(batch);
        }
    }

    /** 立即冲刷缓冲（测试/关闭前用） */
    public void flush() {
        List<T> batch;
        synchronized (lock) {
            if (buffer.isEmpty()) {
                return;
            }
            batch = buffer;
            buffer = new ArrayList<>();
        }
        sendWithResubmit(batch);
    }

    public void close() {
        flush();
        synchronized (lock) {
            closed = true;
        }
        scheduler.shutdownNow();
    }

    /** 发送一批；被拒条目（10700.rejected）立即补发一轮——只补发 rejected，不整批重发 */
    private void sendWithResubmit(List<T> batch) {
        List<T> rejected = sender.send(batch);
        if (rejected == null || rejected.isEmpty()) {
            return;
        }
        List<T> retry = new ArrayList<>(rejected);
        List<T> rejectedAgain = sender.send(retry);
        if (rejectedAgain != null && !rejectedAgain.isEmpty()) {
            // 补发仍被拒：丢弃并告警（lease 迟到类拒收本就无需补偿，02 §6）
            onDropped(rejectedAgain);
        }
    }

    protected void onDropped(List<T> dropped) {
        // 钩子：SDK 侧接 SLI 告警（丢弃计数）
    }

    public int buffered() {
        synchronized (lock) {
            return buffer.size();
        }
    }
}
