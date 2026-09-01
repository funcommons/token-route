package fun.commons.tokenroute.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * helper 函数（05 §3）：num 零值缺省 / str / len / in / min / max。
 */
class TrScriptFunctionsTest {

    static Stream<Arguments> nums() {
        return Stream.of(
                Arguments.of(null, 0.0),
                Arguments.of("", 0.0),
                Arguments.of("abc", 0.0),
                Arguments.of("0.02", 0.02),
                Arguments.of(" 3 ", 3.0),
                Arguments.of(5, 5.0),
                Arguments.of(2.5, 2.5));
    }

    @ParameterizedTest
    @MethodSource("nums")
    void numConvertsSafely(Object in, double expected) {
        assertThat(TrScriptFunctions.num(in).doubleValue()).isEqualTo(expected);
    }

    @Test
    void strHandlesNull() {
        assertThat(TrScriptFunctions.str(null)).isEmpty();
        assertThat(TrScriptFunctions.str(42)).isEqualTo("42");
    }

    @Test
    void lenCountsStringAndCollections() {
        assertThat(TrScriptFunctions.len("abc")).isEqualTo(3);
        assertThat(TrScriptFunctions.len(List.of(1, 2))).isEqualTo(2);
        assertThat(TrScriptFunctions.len(Map.of("a", 1))).isEqualTo(1);
        assertThat(TrScriptFunctions.len(null)).isZero();
    }

    @Test
    void inChecksMembership() {
        assertThat(TrScriptFunctions.in("a", List.of("a", "b"))).isTrue();
        assertThat(TrScriptFunctions.in("c", List.of("a", "b"))).isFalse();
        assertThat(TrScriptFunctions.in("x", null)).isFalse();
        assertThat(TrScriptFunctions.in(null, List.of())).isFalse();
    }

    @Test
    void minMaxOnComparableCollections() {
        assertThat(TrScriptFunctions.min(List.of(3, 1, 2))).isEqualTo(1);
        assertThat(TrScriptFunctions.max(List.of(3, 1, 2))).isEqualTo(3);
        assertThat(TrScriptFunctions.min(List.of())).isNull();
        assertThat(TrScriptFunctions.min(null)).isNull();
    }
}
