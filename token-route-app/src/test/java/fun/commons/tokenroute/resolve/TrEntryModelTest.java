package fun.commons.tokenroute.resolve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EntryJSON 反序列化与条目模型分支（03 §2.1 / 01 §4.2）：坏 JSON 容错、态域参与判定、
 * 降级权重、capacity 字段口径（缺省/非法回落）。
 */
class TrEntryModelTest {

    private static TrEntry parse(String json) {
        return TrEntry.fromJson(json);
    }

    @Test
    void badJsonYieldsNull() {
        assertThat(parse("not-json")).isNull();
        assertThat(parse("{\"entry_id\":")).isNull();
    }

    @Test
    void defaultsApplyForMissingFields() {
        TrEntry e = parse("{\"entry_id\":\"e1\",\"name\":\"n\"}");
        assertThat(e).isNotNull();
        assertThat(e.getWeight()).isEqualTo(100.0);
        assertThat(e.getOrderNo()).isZero();
        assertThat(e.getStatus()).isEqualTo(TrEntry.ACTIVE);
        assertThat(e.getFrozenUntil()).isNull();
        assertThat(e.getDataJson()).isEmpty();
    }

    @Test
    void malformedFieldTypesFallBackToDefaults() {
        TrEntry e = parse("{\"entry_id\":\"e1\",\"weight\":\"heavy\",\"order_no\":\"x\"," +
                "\"status\":[],\"frozen_until\":\"soon\",\"data_json\":\"scalar\"}");
        assertThat(e.getWeight()).isEqualTo(100.0);
        assertThat(e.getOrderNo()).isZero();
        assertThat(e.getFrozenUntil()).isNull();
        assertThat(e.getDataJson()).isEmpty();
    }

    @Test
    void dataJsonMapIsCopied() {
        TrEntry e = parse("{\"entry_id\":\"e1\",\"data_json\":{\"k\":\"v\"}}");
        assertThat(e.getDataJson()).containsEntry("k", "v");
    }

    @Test
    void selectableMatrix() {
        long now = 1_000L;
        assertThat(parse("{\"entry_id\":\"e\",\"status\":\"ACTIVE\"}").selectable(now)).isTrue();
        assertThat(parse("{\"entry_id\":\"e\",\"status\":\"DEGRADED_L1\"}").selectable(now)).isTrue();
        assertThat(parse("{\"entry_id\":\"e\",\"status\":\"DEGRADED_L2\"}").selectable(now)).isFalse();
        assertThat(parse("{\"entry_id\":\"e\",\"status\":\"OFFLINE\"}").selectable(now)).isFalse();
        assertThat(parse("{\"entry_id\":\"e\",\"status\":\"FROZEN\"}").selectable(now)).isFalse();
        assertThat(parse("{\"entry_id\":\"e\",\"status\":\"FROZEN\",\"frozen_until\":2000}").selectable(now)).isFalse();
        assertThat(parse("{\"entry_id\":\"e\",\"status\":\"FROZEN\",\"frozen_until\":500}").selectable(now)).isTrue();
    }

    @Test
    void degradedL1HalvesEffectiveWeight() {
        assertThat(parse("{\"entry_id\":\"e\",\"weight\":8}").effectiveWeight()).isEqualTo(8.0);
        assertThat(parse("{\"entry_id\":\"e\",\"weight\":8,\"status\":\"DEGRADED_L1\"}")
                .effectiveWeight()).isEqualTo(4.0);
    }

    @Test
    void capacityAbsentYieldsNull() {
        assertThat(parse("{\"entry_id\":\"e\"}").capacity()).isNull();
        assertThat(parse("{\"entry_id\":\"e\",\"data_json\":{}}").capacity()).isNull();
    }

    @Test
    void capacityFieldsParsedWithFallbacks() {
        TrEntry e = parse("{\"entry_id\":\"e\",\"data_json\":{\"capacity\":{" +
                "\"max_concurrency\":3,\"rate_limit_value\":2.5,\"rate_window_ms\":5000,\"rate_unit\":\"token\"}}}");
        TrCapacity cap = e.capacity();
        assertThat(cap.maxConcurrency()).isEqualTo(3);
        assertThat(cap.rateLimitValue()).isEqualTo(2.5);
        assertThat(cap.rateWindowMs()).isEqualTo(5000);
        assertThat(cap.rateUnit()).isEqualTo(TrRateUnit.TOKEN);
        assertThat(cap.governed()).isTrue();
        assertThat(cap.rateWindowOrDefault()).isEqualTo(5000L);
    }

    @Test
    void capacityBadUnitFallsBackToRequest() {
        TrEntry e = parse("{\"entry_id\":\"e\",\"data_json\":{\"capacity\":{\"rate_unit\":\"warp\"}}}");
        TrCapacity cap = e.capacity();
        assertThat(cap.rateUnit()).isEqualTo(TrRateUnit.REQUEST);
        assertThat(cap.maxConcurrency()).isNull();
        assertThat(cap.rateLimitValue()).isNull();
        assertThat(cap.governed()).isFalse();
        assertThat(cap.rateWindowOrDefault()).isEqualTo(1000L);
    }

    @Test
    void selectionWeightedRandomRespectsPool() {
        TrEntry a = TrEntry.fromJson("{\"entry_id\":\"a\",\"weight\":1}");
        TrEntry b = TrEntry.fromJson("{\"entry_id\":\"b\",\"weight\":9}");
        for (int i = 0; i < 50; i++) {
            assertThat(TrSelection.weightedRandom(List.of(a, b)).getEntryId()).isIn("a", "b");
        }
        assertThat(TrSelection.weightFirst(List.of(a, b)).getEntryId()).isEqualTo("b");
        assertThat(TrSelection.roundRobin(List.of(a, b), 5).getEntryId()).isIn("a", "b");
    }
}
