package fun.commons.tokenroute.keyspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TrKeySpace 键工厂测试——键名与 03_数据设计 §2 字典逐条对齐。
 */
class TrKeySpaceTest {

    private final TrKeySpace keys = new TrKeySpace("tr");

    @Test
    void entryDataFaceKeys() {
        assertThat(keys.entryIds("llm")).isEqualTo("tr:entry-ids:llm");
        assertThat(keys.entry("llm", "e-1")).isEqualTo("tr:entry:llm:e-1");
        assertThat(keys.feed("llm")).isEqualTo("tr:feed:llm");
    }

    @Test
    void capacityAccountingKeys() {
        assertThat(keys.conc("e-1")).isEqualTo("tr:conc:e-1");
        assertThat(keys.rateReq("e-1")).isEqualTo("tr:rate:e-1:req");
        assertThat(keys.rateUnit("e-1")).isEqualTo("tr:rate:e-1:unit");
        assertThat(keys.seq("e-1")).isEqualTo("tr:seq:e-1");
    }

    @Test
    void runtimeStateKeys() {
        assertThat(keys.affinity("llm", "task-abc-1")).isEqualTo("tr:affinity:llm:task-abc-1");
        assertThat(keys.win("e-1")).isEqualTo("tr:win:e-1");
        assertThat(keys.fail("e-1")).isEqualTo("tr:fail:e-1");
        assertThat(keys.rr("llm")).isEqualTo("tr:rr:llm");
        assertThat(keys.logResolve("llm")).isEqualTo("tr:log:resolve:llm");
        assertThat(keys.logAff("llm")).isEqualTo("tr:log:aff:llm");
        assertThat(keys.logState("e-1")).isEqualTo("tr:log:state:e-1");
        assertThat(keys.schedLock("evaluator")).isEqualTo("tr:sched:lock:v1:evaluator");
    }

    @Test
    void customPrefixIsApplied() {
        TrKeySpace custom = new TrKeySpace("tokr");
        assertThat(custom.entryIds("t1")).isEqualTo("tokr:entry-ids:t1");
        assertThat(custom.affinity("t1", "s1")).isEqualTo("tokr:affinity:t1:s1");
    }

    @Test
    void blankSegmentsAreRejected() {
        // 键段拼进 Redis 键名，空段 = 键碰撞风险，构造期拒绝而非拼出 "tr:entry:llm:"
        assertThatThrownBy(() -> keys.entry("llm", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.affinity("llm", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrKeySpace(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
