package fun.commons.tokenroute.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两用途语义（05 §4/§6）：filter fail-closed / selector 回退语义 + 示例集脚本。
 */
class TrFilterSelectorSemanticsTest {

    private final TrScriptEngine engine = new TrScriptEngine(1000);

    private static Map<String, Object> entry(String id, Map<String, Object> data) {
        return Map.of("entry_id", id, "name", id, "weight", 100.0, "data_json", data);
    }

    @Test
    void filterExample1PriceCapAndModelSupport() {
        TrCompiledScript s = engine.compile("f1", """
                num(params.price) <= num(entry.data_json.max_price) && in(params.model, entry.data_json.models)""");
        var params = Map.<String, Object>of("price", "0.02", "model", "gpt-4o");
        var ok = entry("e-1", Map.of("max_price", 0.05, "models", List.of("gpt-4o", "gpt-4o-mini")));
        var noModel = entry("e-2", Map.of("max_price", 0.05, "models", List.of("gpt-3.5")));
        var noPrice = entry("e-3", Map.of("max_price", 0.01, "models", List.of("gpt-4o")));

        assertThat(engine.evalFilter(s, params, ok, 1L)).isTrue();
        assertThat(engine.evalFilter(s, params, noModel, 1L)).isFalse();
        assertThat(engine.evalFilter(s, params, noPrice, 1L)).isFalse();
    }

    @Test
    void filterExample2TenantWhitelist() {
        TrCompiledScript s = engine.compile("f2",
                "len(entry.data_json.tenants) == 0 || in(params.tenant, entry.data_json.tenants)");
        var empty = entry("e-1", Map.of("tenants", List.of()));
        var gated = entry("e-2", Map.of("tenants", List.of("t-9")));

        assertThat(engine.evalFilter(s, Map.of("tenant", "t-1"), empty, 1L)).isTrue();
        assertThat(engine.evalFilter(s, Map.of("tenant", "t-1"), gated, 1L)).isFalse();
        assertThat(engine.evalFilter(s, Map.of("tenant", "t-9"), gated, 1L)).isTrue();
    }

    @Test
    void filterNonBooleanResultIsFailClosed() {
        // 正常求值但非 Boolean → 出局（05 §4），不算故障
        TrCompiledScript number = engine.compile("f-n", "1 + 1");
        TrCompiledScript string = engine.compile("f-s", "'yes'");
        TrCompiledScript nullResult = engine.compile("f-null", "null");
        var e = entry("e-1", Map.of());
        assertThat(engine.evalFilter(number, Map.of(), e, 1L)).isFalse();
        assertThat(engine.evalFilter(string, Map.of(), e, 1L)).isFalse();
        assertThat(engine.evalFilter(nullResult, Map.of(), e, 1L)).isFalse();
    }

    @Test
    void filterRuntimeErrorThrowsForCallerDegrade() {
        // 求值异常（除零）→ 抛执行异常；filter 出局 + [TR-SCRIPT] 计数由 resolve 层处理（05 §5）
        TrCompiledScript bad = engine.compile("f-bad", "def x = 1 / 0\ntrue");
        assertThatThrownBy(() -> engine.evalFilter(bad, Map.of(), entry("e-1", Map.of()), 1L))
                .isInstanceOf(TrScriptExecutionException.class);
    }

    @Test
    void selectorExample1PriceFirstSkipsMissingPrice() {
        // 05 §6 num() 零值陷阱警示：先剔除缺价条目再 min
        TrCompiledScript s = engine.compile("s1", """
                def priced = entries.findAll { it.data_json.unit_price != null }
                priced.isEmpty() ? null : priced.min { num(it.data_json.unit_price) }.entry_id""");
        var cheap = entry("e-cheap", Map.of("unit_price", 0.01));
        var dear = entry("e-dear", Map.of("unit_price", 0.5));
        var noPrice = entry("e-none", Map.of());

        assertThat(engine.evalSelector(s, Map.of(), List.of(dear, cheap, noPrice), 1L))
                .contains("e-cheap");
        assertThat(engine.evalSelector(s, Map.of(), List.of(noPrice), 1L))
                .isEmpty();
    }

    @Test
    void selectorExample2WeightedWithHealthFactor() {
        TrCompiledScript s = engine.compile("s2", """
                def best = entries.max { num(it.weight) * (it.status == 'ACTIVE' ? 1.0 : 0.5) }
                best.entry_id""");
        var active = new java.util.HashMap<>(entry("e-a", Map.of()));
        active.put("weight", 100.0);
        active.put("status", "ACTIVE");
        var degraded = new java.util.HashMap<>(entry("e-b", Map.of()));
        degraded.put("weight", 150.0);
        degraded.put("status", "DEGRADED_L1");

        assertThat(engine.evalSelector(s, Map.of(), List.of(active, degraded), 1L))
                .contains("e-a");
    }

    @Test
    void selectorInvalidReturnFallsBack() {
        // 返回不存在 entry_id / null / 非 string → Optional.empty（回退默认策略，非故障，05 §4）
        TrCompiledScript missing = engine.compile("s-m", "'e-ghost'");
        TrCompiledScript nullResult = engine.compile("s-null", "null");
        TrCompiledScript nonString = engine.compile("s-num", "42");
        var candidates = List.of(entry("e-1", Map.of()));

        assertThat(engine.evalSelector(missing, Map.of(), candidates, 1L)).isEmpty();
        assertThat(engine.evalSelector(nullResult, Map.of(), candidates, 1L)).isEmpty();
        assertThat(engine.evalSelector(nonString, Map.of(), candidates, 1L)).isEmpty();
    }

    @Test
    void selectorRuntimeErrorThrowsForCallerDegrade() {
        TrCompiledScript bad = engine.compile("s-bad", "entries.missingMethod()");
        assertThatThrownBy(() -> engine.evalSelector(bad, Map.of(), List.of(entry("e-1", Map.of())), 1L))
                .isInstanceOf(TrScriptExecutionException.class);
    }

    @Test
    void bindingsExposeParamsAndNow() {
        TrCompiledScript s = engine.compile("b", "params.k == 'v' && now > 0");
        assertThat(engine.evalFilter(s, Map.of("k", "v"), entry("e", Map.of()), 42L)).isTrue();
        assertThat(Optional.ofNullable(engine)).isNotNull();
    }
}
