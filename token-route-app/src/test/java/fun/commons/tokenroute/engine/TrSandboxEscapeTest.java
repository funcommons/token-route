package fun.commons.tokenroute.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 沙箱逃逸向量全套（05 §7）：全部编译期拒绝 → 10630。
 */
class TrSandboxEscapeTest {

    private final TrScriptEngine engine = new TrScriptEngine(100);

    @ParameterizedTest(name = "编译期拒绝: {0}")
    @ValueSource(strings = {
            "System.exit(1)",
            "System.getProperty('user.dir')",
            "Runtime.getRuntime().exec('ls')",
            "Runtime.getRuntime().availableProcessors()",
            "Thread.sleep(10000)",
            "new Thread({}).start()",
            "new File('/tmp/x')",
            "new java.io.File('/tmp/x')",
            "new ProcessBuilder('ls').start()",
            "evaluate('1+1')",
            "Class.forName('java.lang.Runtime')",
            "this.class.classLoader.loadClass('java.lang.Runtime')",
            "'ls'.execute()",
            "'x'.bytes",
            "while(true) {}",
            "for(;;) {}",
            "import java.io.File\nnew File('/tmp/x')",
            "System.currentTimeMillis()"
    })
    void escapeVectorsAreRejectedAtCompileTime(String script) {
        assertThatThrownBy(() -> engine.compile("escape", script))
                .isInstanceOf(TrScriptCompileException.class)
                .hasMessageContaining("10630");
    }

    @Test
    void compileFailureNamesTheScript() {
        assertThatThrownBy(() -> engine.compile("bad-table-script", "while(true) {}"))
                .hasMessageContaining("bad-table-script");
    }

    @Test
    void benignScriptsCompile() {
        org.assertj.core.api.Assertions.assertThatCode(() ->
                        engine.compile("benign", "def priced = [1, 2, 3]\ntrue"))
                .doesNotThrowAnyException();
    }
}
