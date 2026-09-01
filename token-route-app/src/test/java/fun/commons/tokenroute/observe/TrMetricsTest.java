package fun.commons.tokenroute.observe;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SLI 埋点（⑤.1）：计数器/计时器/gauge 落 SimpleMeterRegistry 断言；noop 模式安全空转。
 */
class TrMetricsTest {

    @Test
    void resolveCountersAndTimer() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        TrMetrics m = new TrMetrics(r);
        m.resolve(0, false);
        m.resolve(0, false);
        m.resolve(0, true);
        assertThat(r.get("tr.resolve").tag("outcome", "ok").counter().count()).isEqualTo(2.0);
        assertThat(r.get("tr.resolve").tag("outcome", "empty").counter().count()).isEqualTo(1.0);
        assertThat(r.get("tr.resolve.latency").timer().count()).isEqualTo(3);
    }

    @Test
    void reportCountersAcceptAndRejectSeparately() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        TrMetrics m = new TrMetrics(r);
        m.report(0, 3, 1);
        m.report(0, 0, 2);
        assertThat(r.get("tr.report").counter().count()).isEqualTo(3.0);
        assertThat(r.get("tr.report.rejected").counter().count()).isEqualTo(3.0);
        assertThat(r.get("tr.report.latency").timer().count()).isEqualTo(2);
    }

    @Test
    void feedPullOutcomeTagged() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        TrMetrics m = new TrMetrics(r);
        m.feedPull(true);
        m.feedPull(false);
        assertThat(r.get("tr.feed.pull").tag("result", "ok").counter().count()).isEqualTo(1.0);
        assertThat(r.get("tr.feed.pull").tag("result", "fail").counter().count()).isEqualTo(1.0);
    }

    @Test
    void stateTransitionTaggedByTarget() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        TrMetrics m = new TrMetrics(r);
        m.stateTransition("FROZEN");
        m.stateTransition("ACTIVE");
        m.stateTransition("FROZEN");
        assertThat(r.get("tr.state.transition").tag("to", "FROZEN").counter().count()).isEqualTo(2.0);
        assertThat(r.get("tr.state.transition").tag("to", "ACTIVE").counter().count()).isEqualTo(1.0);
    }

    @Test
    void scriptDegradedGaugeTracksAtomicLong() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        TrMetrics m = new TrMetrics(r);
        AtomicLong degraded = new AtomicLong(7);
        m.gauge("tr.script.degraded", degraded);
        degraded.set(9);
        assertThat(r.get("tr.script.degraded").gauge().value()).isEqualTo(9.0);
    }

    @Test
    void noopSafelyDoesNothing() {
        TrMetrics noop = TrMetrics.noop();
        assertThatCode(() -> {
            noop.resolve(0, false);
            noop.report(0, 1, 0);
            noop.feedPull(true);
            noop.stateTransition("FROZEN");
            noop.gauge("x", new AtomicLong());
        }).doesNotThrowAnyException();
    }
}
