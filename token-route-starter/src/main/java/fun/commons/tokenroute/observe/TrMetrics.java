package fun.commons.tokenroute.observe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;


import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SLI 指标埋点（docs/用户文档/SLI告警建议.md §指标对照；/actuator/prometheus 暴露）。
 * 生产经 Spring 装配 MeterRegistry；测试/未装配场景用 {@link #noop()}——全部方法安全空转。
 * 指标命名（Prometheus 渲染后）：tr_resolve_total{outcome} / tr_resolve_latency_seconds /
 * tr_report_total / tr_report_rejected_total / tr_report_latency_seconds /
 * tr_feed_pull_total{result} / tr_state_transition_total{to} / tr_script_degraded。
 */
public class TrMetrics {

    private static final TrMetrics NOOP = new TrMetrics(null);

    private final MeterRegistry registry;
    private final Timer resolveLatency;
    private final Timer reportLatency;
    private final Counter resolveOk;
    private final Counter resolveEmpty;
    private final Counter reportAccepted;
    private final Counter reportRejected;
    private final Counter feedOk;
    private final Counter feedFail;
    private final Counter evaluatorRun;

    public TrMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry == null) {
            this.resolveLatency = null;
            this.reportLatency = null;
            this.resolveOk = null;
            this.resolveEmpty = null;
            this.reportAccepted = null;
            this.reportRejected = null;
            this.feedOk = null;
            this.feedFail = null;
            this.evaluatorRun = null;
            return;
        }
        this.resolveLatency = Timer.builder("tr.resolve.latency").description("resolve 决策耗时").register(registry);
        this.reportLatency = Timer.builder("tr.report.latency").description("report 回填耗时").register(registry);
        this.resolveOk = Counter.builder("tr.resolve").description("resolve 次数")
                .tag("outcome", "ok").register(registry);
        this.resolveEmpty = Counter.builder("tr.resolve").description("resolve 次数")
                .tag("outcome", "empty").register(registry);
        this.reportAccepted = Counter.builder("tr.report").description("report 受理条数").register(registry);
        this.reportRejected = Counter.builder("tr.report.rejected").description("report 拒收条数").register(registry);
        this.feedOk = Counter.builder("tr.feed.pull").description("FEED 拉取次数")
                .tag("result", "ok").register(registry);
        this.feedFail = Counter.builder("tr.feed.pull").description("FEED 拉取次数")
                .tag("result", "fail").register(registry);
        this.evaluatorRun = Counter.builder("tr.evaluator.run").description("评估器执行轮数（持锁成功才计数）")
                .register(registry);
    }

    public static TrMetrics noop() {
        return NOOP;
    }

    /** resolve 决策出口：empty=true 时 outcome=empty（SLI#1 EMPTY 率、SLI#2 P99） */
    public void resolve(long startMs, boolean empty) {
        if (registry == null) {
            return;
        }
        (empty ? resolveEmpty : resolveOk).increment();
        resolveLatency.record(System.currentTimeMillis() - startMs, TimeUnit.MILLISECONDS);
    }

    /** report 批量出口（SLI#3 P99；受理/拒收条数分别累计） */
    public void report(long startMs, int accepted, int rejected) {
        if (registry == null) {
            return;
        }
        reportAccepted.increment(accepted);
        reportRejected.increment(rejected);
        reportLatency.record(System.currentTimeMillis() - startMs, TimeUnit.MILLISECONDS);
    }

    /** FEED 拉取结果（SLI#4 失败率；失败保旧值语义不变） */
    public void feedPull(boolean success) {
        if (registry == null) {
            return;
        }
        (success ? feedOk : feedFail).increment();
    }

    /** 评估器持锁执行一轮（双实例互斥实测：两实例计数之和 ≈ 分钟轮数，而非两倍） */
    public void evaluatorRun() {
        if (registry == null) {
            return;
        }
        evaluatorRun.increment();
    }

    /** 条目状态���移（SLI#7 突增；to ∈ ACTIVE/DEGRADED_L1~L3/FROZEN/OFFLINE，tag 组合有限由 registry 缓存） */
    public void stateTransition(String to) {
        if (registry == null) {
            return;
        }
        registry.counter("tr.state.transition", "to", to).increment();
    }

    /** 进程内累计计数器 → gauge（SLI#5 script 降级增速） */
    public void gauge(String name, AtomicLong ref) {
        if (registry == null) {
            return;
        }
        Gauge.builder(name, ref, AtomicLong::doubleValue).strongReference(true).register(registry);
    }
}
