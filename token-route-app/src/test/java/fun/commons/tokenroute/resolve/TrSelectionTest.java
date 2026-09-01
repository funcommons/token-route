package fun.commons.tokenroute.resolve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置三策略纯函数（01 §5.1 第 4 步 / 02 §8 weight 语义）。
 */
class TrSelectionTest {

    private static TrEntry entry(String id, double weight, int orderNo, String status) {
        String json = "{\"entry_id\":\"" + id + "\",\"name\":\"" + id + "\",\"weight\":" + weight
                + ",\"order_no\":" + orderNo + ",\"status\":\"" + status + "\",\"data_json\":{}}";
        return TrEntry.fromJson(json);
    }

    @Test
    void weightedRandomNeverPicksNonPositiveWeight() {
        List<TrEntry> pool = List.of(entry("a", 0, 1, "ACTIVE"), entry("b", -5, 2, "ACTIVE"), entry("c", 100, 3, "ACTIVE"));
        for (int i = 0; i < 200; i++) {
            TrEntry picked = TrSelection.weightedRandom(pool);
            assertThat(picked.getEntryId()).isEqualTo("c");
        }
    }

    @Test
    void weightedRandomDistributesByWeight() {
        List<TrEntry> pool = List.of(entry("a", 75, 1, "ACTIVE"), entry("b", 25, 2, "ACTIVE"));
        int aCount = 0;
        for (int i = 0; i < 2000; i++) {
            if ("a".equals(TrSelection.weightedRandom(pool).getEntryId())) {
                aCount++;
            }
        }
        assertThat(aCount).isBetween(1300, 1700); // 75% ±10pp
    }

    @Test
    void roundRobinWalksOrderByOrderNo() {
        List<TrEntry> pool = List.of(entry("b", 100, 2, "ACTIVE"), entry("a", 100, 1, "ACTIVE"));
        assertThat(TrSelection.roundRobin(pool, 1).getEntryId()).isEqualTo("a");
        assertThat(TrSelection.roundRobin(pool, 2).getEntryId()).isEqualTo("b");
        assertThat(TrSelection.roundRobin(pool, 3).getEntryId()).isEqualTo("a");
    }

    @Test
    void weightFirstAppliesDegradationFactor() {
        // ACTIVE 100 vs L1 150 → 有效 75 < 100 → ACTIVE 胜出（01 §4.2 降级 ×0.5）
        List<TrEntry> pool = List.of(entry("active", 100, 1, "ACTIVE"), entry("l1", 150, 2, "DEGRADED_L1"));
        assertThat(TrSelection.weightFirst(pool).getEntryId()).isEqualTo("active");
    }

    @Test
    void weightFirstAllowsNegativePriceFirst() {
        // 负权重 = 价格优先变形：weight = -price，最大化权重即最低价（cheap=-10 > pricey=-100）
        List<TrEntry> pool = List.of(entry("pricey", -100, 1, "ACTIVE"), entry("cheap", -10, 2, "ACTIVE"));
        assertThat(TrSelection.weightFirst(pool).getEntryId()).isEqualTo("cheap");
    }

    @Test
    void emptyPoolYieldsNull() {
        assertThat(TrSelection.weightedRandom(List.of())).isNull();
        assertThat(TrSelection.roundRobin(List.of(), 1)).isNull();
        assertThat(TrSelection.weightFirst(List.of())).isNull();
    }
}
