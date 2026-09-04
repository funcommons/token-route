package fun.commons.tokenroute.resolve;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确定性 entry_id（02 §5）：e-{hash(tid,name)}，零映射键；Redis 丢失重建后 ID 稳定。
 */
class TrEntryIdTest {

    @Test
    void deterministicAcrossCalls() {
        assertThat(TrEntryId.of("llm", "channel-a")).isEqualTo(TrEntryId.of("llm", "channel-a"));
    }

    @Test
    void distinctPerNameAndPerTable() {
        assertThat(TrEntryId.of("llm", "channel-a")).isNotEqualTo(TrEntryId.of("llm", "channel-b"));
        assertThat(TrEntryId.of("llm", "channel-a")).isNotEqualTo(TrEntryId.of("other", "channel-a"));
    }

    @Test
    void formatIsEPlus16Hex() {
        assertThat(TrEntryId.of("t", "n")).matches("e-[0-9a-f]{16}");
    }

    @Test
    void colonSeparatorPreventsCrossProductCollision() {
        // "a:bc" 与 "ab:c" 必须不同（无分隔符拼接会碰撞）
        assertThat(TrEntryId.of("a", "bc")).isNotEqualTo(TrEntryId.of("ab", "c"));
    }

    @Test
    void blankSegmentsRejected() {
        assertThatThrownBy(() -> TrEntryId.of(" ", "n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrEntryId.of("t", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
