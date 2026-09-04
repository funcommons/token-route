package fun.commons.tokenroute.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 超时中断 + 编译缓存（05 §2 第二件套/编译缓存）。
 */
class TrScriptTimeoutCacheTest {

    @Test
    @Timeout(5)
    void longRunningScriptIsInterrupted() {
        TrScriptEngine engine = new TrScriptEngine(100);
        TrCompiledScript heavy = engine.compile("heavy", "(1..8000000).collect { it + 1 }");

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> engine.evalSelector(heavy, Map.of(), List.of(), 1L))
                .isInstanceOf(TrScriptTimeoutException.class)
                .hasMessageContaining("100");
        // 超时在预算（100ms）量级返回，不等脚本自然跑完
        assertThat(System.currentTimeMillis() - start).isLessThan(5000);
    }

    @Test
    void sameNameReusesCompiledClass() {
        TrScriptEngine engine = new TrScriptEngine(100);
        TrCompiledScript first = engine.compile("s", "true");
        TrCompiledScript second = engine.compile("s", "true");
        org.assertj.core.api.Assertions.assertThat(second).isSameAs(first);
    }

    @Test
    void compileIsFailClosedOnSyntaxError() {
        TrScriptEngine engine = new TrScriptEngine(100);
        assertThatThrownBy(() -> engine.compile("broken", "def x = = 1"))
                .isInstanceOf(TrScriptCompileException.class)
                .hasMessageContaining("10630");
    }
}
