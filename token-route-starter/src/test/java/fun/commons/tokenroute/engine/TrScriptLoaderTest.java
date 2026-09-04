package fun.commons.tokenroute.engine;

import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 脚本装载 fail-fast（05 §3）：启动编译失败 → 10630 指名表/脚本 → 拒绝启动。
 */
class TrScriptLoaderTest {

    private final TrScriptEngine engine = new TrScriptEngine(1000);

    private TrTableDefinition table(String name, String filterScript) {
        TrTableDefinition d = new TrTableDefinition();
        d.setName(name);
        d.setRefreshUrl("http://up/feed");
        d.setFilterScript(filterScript);
        return d;
    }

    @Test
    void goodScriptCompilesAndRegistersAndEvaluates() {
        TrScriptRegistry registry = new TrScriptLoader(engine, new TrScriptRegistry())
                .loadAll(TrTableRegistry.load(List.of(table("t-good", "classpath:scripts/good-filter.groovy"))));

        assertThat(registry.find("t-good" + TrScriptRegistry.FILTER_SUFFIX)).isPresent();
        TrCompiledScript compiled = registry.find("t-good" + TrScriptRegistry.FILTER_SUFFIX).orElseThrow();
        var publicEntry = Map.<String, Object>of("entry_id", "e-1", "data_json", Map.of("tenants", List.of()));
        assertThat(engine.evalFilter(compiled, Map.of("tenant", "t-1"), publicEntry, 1L)).isTrue();
    }

    @Test
    void badScriptFailsFastNamingTable() {
        TrScriptLoader loader = new TrScriptLoader(engine, new TrScriptRegistry());
        assertThatThrownBy(() -> loader.loadAll(
                TrTableRegistry.load(List.of(table("t-bad", "classpath:scripts/bad-filter.groovy")))))
                .isInstanceOf(TrScriptCompileException.class)
                .hasMessageContaining("10630")
                .hasMessageContaining("t-bad");
    }

    @Test
    void missingScriptFileFailsFastNamingTable() {
        TrScriptLoader loader = new TrScriptLoader(engine, new TrScriptRegistry());
        assertThatThrownBy(() -> loader.loadAll(
                TrTableRegistry.load(List.of(table("t-missing", "classpath:scripts/nope.groovy")))))
                .isInstanceOf(TrScriptCompileException.class)
                .hasMessageContaining("10630")
                .hasMessageContaining("t-missing");
    }
}
