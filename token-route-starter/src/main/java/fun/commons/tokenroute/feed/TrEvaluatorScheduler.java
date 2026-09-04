package fun.commons.tokenroute.feed;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 评估器节拍器（原 app @Scheduled 平移为自管线程，01 §5.3）：
 * fixedDelay 60s / initialDelay 60s 与原节奏一致；**不依赖宿主 @EnableScheduling**——
 * 嵌入式宿主的调度语义不被本库改写。daemon 线程随 JVM 退出，@PreDestroy 优雅停。
 */
public class TrEvaluatorScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrEvaluatorScheduler.class);

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tr-evaluator");
                t.setDaemon(true);
                return t;
            });

    public TrEvaluatorScheduler(TrEvaluateJob job) {
        executor.scheduleWithFixedDelay(() -> {
            try {
                job.evaluate();
            } catch (Exception e) {
                log.warn("[TR-STATE] 评估器轮次异常（下一轮继续）: {}", e.getMessage());
            }
        }, 60_000, 60_000, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
