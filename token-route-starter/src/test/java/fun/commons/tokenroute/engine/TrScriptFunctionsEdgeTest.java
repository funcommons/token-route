package fun.commons.tokenroute.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * helper 函数边界分支（05 §3）：len 非集合标量、in 字符串集合与 null 成员、
 * extremum 非可比元素与空集。
 */
class TrScriptFunctionsEdgeTest {

    @Test
    void lenReturnsZeroForPlainScalars() {
        assertThat(TrScriptFunctions.len(42)).isZero();
        assertThat(TrScriptFunctions.len(true)).isZero();
    }

    @Test
    void inFallsBackToStringComparisonForNonCollections() {
        assertThat(TrScriptFunctions.in("a", "a")).isTrue();
        assertThat(TrScriptFunctions.in("a", "b")).isFalse();
        assertThat(TrScriptFunctions.in(null, "a")).isFalse();
    }

    @Test
    void inScansNullMembersExplicitly() {
        List<Object> withNull = java.util.Arrays.asList("x", null); // List.of 不允许 null 成员
        assertThat(TrScriptFunctions.in(null, withNull)).isTrue();
        assertThat(TrScriptFunctions.in("x", withNull)).isTrue();
    }

    @Test
    void minMaxHandleStringsAndNonComparable() {
        assertThat(TrScriptFunctions.min(List.of("b", "a"))).isEqualTo("a");
        assertThat(TrScriptFunctions.max(List.of("a", "b"))).isEqualTo("b");
        assertThat(TrScriptFunctions.max(List.of())).isNull();
        assertThat(TrScriptFunctions.max(null)).isNull();
        // 全部元素不可比 → 过滤后空 → null
        assertThat(TrScriptFunctions.min(List.of(new Object(), new Object()))).isNull();
    }
}
