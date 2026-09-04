package fun.commons.tokenroute.resolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.config.TrStrategyType;
import fun.commons.tokenroute.engine.TrCompiledScript;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptExecutionException;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.observe.TrMetrics;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;

/**
 * resolve 决策全流程分支覆盖（01 §5.1）：态域过滤 / 冷启动回源 / filter 脚本降级 /
 * 亲和三判（HIT/CAPACITY/MISS）/ 四策略 / L1 判定矩阵（NEW/EXISTED/CAPACITY）/ 故障 EMPTY。
 * Redis 数据面以内存桩（entryStore）模拟，Lua 判定按脚本桩定值。
 */
class TrResolveServiceBranchTest {

    private static final long NOW = 1_700_000_000_000L;

    private final TrKeySpace keys = new TrKeySpace("tr");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Set<String>> tableIds = new HashMap<>();
    private final Map<String, String> entryStore = new HashMap<>();
    private String affinityBound;

    private StringRedisTemplate t;
    private TrScriptRegistry scripts;
    private TrScriptEngine engine;
    private TrFeedBackfill backfill;
    private TrTableRegistry registry;
    private TrResolveService service;

    @BeforeEach
    void setUp() {
        TrRedis redis = mock(TrRedis.class);
        t = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.stringTemplate()).thenReturn(t);
        scripts = mock(TrScriptRegistry.class);
        when(scripts.find(anyString())).thenReturn(Optional.empty());
        engine = mock(TrScriptEngine.class);
        backfill = mock(TrFeedBackfill.class);
        reloadTables(def("t1", TrStrategyType.WEIGHTED_RANDOM, true, "ACTIVE"));

        when(t.hasKey(anyString())).thenReturn(true);
        when(t.opsForSet().members(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            return tableIds.get(k.substring(k.lastIndexOf(':') + 1));
        });
        when(t.opsForValue().multiGet(anyList())).thenAnswer(inv -> {
            List<String> ks = inv.getArgument(0);
            List<String> out = new ArrayList<>();
            for (String k : ks) {
                out.add(entryStore.get(k.substring(k.lastIndexOf(':') + 1)));
            }
            return out;
        });
        when(t.opsForValue().get(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            if (k.startsWith("tr:affinity:")) {
                return affinityBound;
            }
            return entryStore.get(k.substring(k.lastIndexOf(':') + 1));
        });
        doReturn(List.of("NEW", "e1")).when(t).execute(same(TrLua.RESOLVE_BIND), anyList(), any(Object[].class));

        service = new TrResolveService(redis, keys, registry, scripts, engine, backfill,
                mapper, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), TrMetrics.noop());
    }

    private void reloadTables(TrTableDefinition... defs) {
        registry = TrTableRegistry.load(List.of(defs));
        service = new TrResolveService(mockRedisWith(t), keys, registry, scripts, engine, backfill,
                mapper, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), TrMetrics.noop());
    }

    private TrRedis mockRedisWith(StringRedisTemplate template) {
        TrRedis redis = mock(TrRedis.class);
        when(redis.stringTemplate()).thenReturn(template);
        return redis;
    }

    private static TrTableDefinition def(String name, TrStrategyType st, boolean affinity, String status) {
        TrTableDefinition d = new TrTableDefinition();
        d.setName(name);
        d.setRefreshUrl("http://feed.example/" + name);
        d.setStrategyType(st);
        d.setAffinityEnabled(affinity);
        d.setStatus(status);
        return d;
    }

    private static TrResolveRequest req(String tid, String session, Map<String, Object> params) {
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId(tid);
        r.setSessionId(session);
        r.setBizParams(params);
        return r;
    }

    private static String entryJson(String eid, String status, Long frozenUntil,
                                    Map<String, Object> capacity) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("entry_id", eid);
        m.put("name", "n-" + eid);
        m.put("weight", 2);
        if (status != null) {
            m.put("status", status);
        }
        if (frozenUntil != null) {
            m.put("frozen_until", frozenUntil);
        }
        if (capacity != null) {
            m.put("data_json", Map.of("capacity", capacity));
        }
        return new ObjectMapper().writeValueAsString(m);
    }

    private void seed(String tid, String... eids) throws Exception {
        tableIds.put(tid, new LinkedHashSet<>(List.of(eids)));
        for (String eid : eids) {
            entryStore.put(eid, entryJson(eid, null, null, null));
        }
    }

    @Test
    void unknownTableThrows() {
        assertThatThrownBy(() -> service.resolve(req("ghost", null, null), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void offlineTableIsEmptyWithReason() {
        reloadTables(def("t1", TrStrategyType.WEIGHTED_RANDOM, true, "OFFLINE"));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getReasons()).containsExactly(TrResolveResponse.R_TABLE_OFFLINE);
    }

    @Test
    void coldStartPullFailsYieldsTableEmpty() {
        tableIds.clear();
        when(t.hasKey(anyString())).thenReturn(false);
        when(backfill.pull("t1")).thenReturn(false);
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getReasons()).containsExactly(TrResolveResponse.R_TABLE_EMPTY);
    }

    @Test
    void coldStartPullSucceedsResolvesWithNone() throws Exception {
        tableIds.clear();
        seed("t1", "e1");
        when(t.hasKey(anyString())).thenReturn(false, true);
        when(backfill.pull("t1")).thenReturn(true);
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getAffinity()).isEqualTo(TrResolveResponse.AFF_NONE);
        assertThat(r.getLeaseId()).isNotBlank();
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void existingKeyTriggersLazyRefreshAsync() throws Exception {
        seed("t1", "e1");
        when(t.opsForValue().increment(anyString())).thenReturn(1L);
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void missingEntryRecoveredByBackfill() throws Exception {
        seed("t1", "e1");
        entryStore.clear(); // MGET 全 miss
        when(backfill.pull("t1")).thenReturn(true);
        entryStore.put("e1", entryJson("e1", null, null, null));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void missingEntryStaysMissingWhenPullFails() {
        tableIds.put("t1", new LinkedHashSet<>(List.of("e1")));
        entryStore.clear();
        when(backfill.pull("t1")).thenReturn(false);
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getReasons()).containsExactly(TrResolveResponse.R_ALL_FILTERED);
    }

    @Test
    void stateFilterExcludesFrozenAndDeepDegraded() throws Exception {
        tableIds.put("t1", new LinkedHashSet<>(List.of("e1", "e2", "e3")));
        entryStore.put("e1", entryJson("e1", "FROZEN", NOW + 60_000, null));   // 未到期 → 出局
        entryStore.put("e2", entryJson("e2", "DEGRADED_L2", null, null));      // L2 不参与
        entryStore.put("e3", entryJson("e3", "ACTIVE", null, null));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e3");
    }

    @Test
    void frozenExpiredLazilyRejoins() throws Exception {
        tableIds.put("t1", new LinkedHashSet<>(List.of("e1")));
        entryStore.put("e1", entryJson("e1", "FROZEN", NOW - 60_000, null));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void filterScriptKeepsMatchingOnly() throws Exception {
        seed("t1", "e1", "e2");
        TrCompiledScript filter = mock(TrCompiledScript.class);
        when(scripts.find("t1:filter")).thenReturn(Optional.of(filter));
        when(engine.evalFilter(any(), anyMap(), anyMap(), anyLong()))
                .thenAnswer(inv -> "e1".equals(((Map<?, ?>) inv.getArgument(2)).get("entry_id")));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void filterScriptExceptionDegradesEntry() throws Exception {
        seed("t1", "e1");
        TrCompiledScript filter = mock(TrCompiledScript.class);
        when(scripts.find("t1:filter")).thenReturn(Optional.of(filter));
        when(engine.evalFilter(any(), anyMap(), anyMap(), anyLong()))
                .thenThrow(new TrScriptExecutionException("t1:filter", "eval", new RuntimeException("x")));
        long before = service.scriptDegradedTotal();
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getReasons()).contains(TrResolveResponse.R_SCRIPT_DEGRADED);
        assertThat(service.scriptDegradedTotal()).isEqualTo(before + 1);
    }

    @Test
    void affinityHitReturnsBoundEntryDirectly() throws Exception {
        seed("t1", "e1", "e2");
        affinityBound = "e1";
        doReturn(List.of("HIT")).when(t).execute(same(TrLua.AFFINITY_HIT), anyList(), any(Object[].class));
        TrResolveResponse r = service.resolve(req("t1", "s1", null), null);
        assertThat(r.getAffinity()).isEqualTo(TrResolveResponse.AFF_HIT);
        assertThat(r.getEntryId()).isEqualTo("e1");
        assertThat(r.getLeaseId()).isNotBlank();
    }

    @Test
    void affinityCapacityFallsBackToRebind() throws Exception {
        seed("t1", "e1");
        affinityBound = "e1";
        doReturn(List.of("CAPACITY")).when(t).execute(same(TrLua.AFFINITY_HIT), anyList(), any(Object[].class));
        TrResolveResponse r = service.resolve(req("t1", "s1", null), null);
        assertThat(r.getAffinity()).isEqualTo(TrResolveResponse.AFF_NEW);
    }

    @Test
    void affinityMissFallsThroughToStrategy() throws Exception {
        seed("t1", "e1");
        affinityBound = "e1";
        doReturn(List.of("MISS")).when(t).execute(same(TrLua.AFFINITY_HIT), anyList(), any(Object[].class));
        TrResolveResponse r = service.resolve(req("t1", "s1", null), null);
        assertThat(r.getAffinity()).isEqualTo(TrResolveResponse.AFF_NEW);
    }

    @Test
    void affinityBoundEntryMissingSkipsHitPath() throws Exception {
        seed("t1", "e1");
        affinityBound = "ghost";
        TrResolveResponse r = service.resolve(req("t1", "s1", null), null);
        assertThat(r.getAffinity()).isEqualTo(TrResolveResponse.AFF_NEW);
    }

    @Test
    void capacityRetryUntilPoolEmptyYieldsCapacityExhausted() throws Exception {
        seed("t1", "e1", "e2");
        doReturn(List.of("CAPACITY")).when(t).execute(same(TrLua.RESOLVE_BIND), anyList(), any(Object[].class));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getReasons()).containsExactly(TrResolveResponse.R_CAPACITY);
    }

    @Test
    void existedVerdictAdoptsWinner() throws Exception {
        seed("t1", "e1", "e2");
        doReturn(List.of("EXISTED", "e2")).when(t).execute(same(TrLua.RESOLVE_BIND), anyList(), any(Object[].class));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getAffinity()).isEqualTo(TrResolveResponse.AFF_HIT);
        assertThat(r.getEntryId()).isEqualTo("e2");
        assertThat(r.getLeaseId()).isNull();
    }

    @Test
    void existedWinnerMissingContinuesToNextCandidate() throws Exception {
        // 单候选保证确定性：首轮 EXISTED 指向缺失胜者 → 不出局重选 → 次轮 NEW
        seed("t1", "e1");
        doReturn(List.of("EXISTED", "ghost"))
                .doReturn(List.of("NEW", "e1"))
                .when(t).execute(same(TrLua.RESOLVE_BIND), anyList(), any(Object[].class));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
        assertThat(r.getLeaseId()).isNotBlank();
    }

    @Test
    void roundRobinUsesCursor() throws Exception {
        reloadTables(def("t1", TrStrategyType.ROUND_ROBIN, false, "ACTIVE"));
        seed("t1", "e1");
        when(t.opsForValue().increment(anyString())).thenReturn(1L);
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void weightFirstStrategyResolves() throws Exception {
        reloadTables(def("t1", TrStrategyType.WEIGHT_FIRST, false, "ACTIVE"));
        seed("t1", "e1");
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void scriptSelectorPicksDesignatedEntry() throws Exception {
        // SCRIPT 策略种子要求 selector_script 非空，直接带脚本路径重建注册表
        TrTableDefinition d = def("t1", TrStrategyType.SCRIPT, false, "ACTIVE");
        d.setSelectorScript("file:/x.groovy");
        registry = TrTableRegistry.load(List.of(d));
        service = new TrResolveService(mockRedisWith(t), keys, registry, scripts, engine, backfill,
                mapper, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), TrMetrics.noop());
        seed("t1", "e1", "e2");
        TrCompiledScript selector = mock(TrCompiledScript.class);
        when(scripts.find("t1:selector")).thenReturn(Optional.of(selector));
        when(engine.evalSelector(any(), anyMap(), anyList(), anyLong())).thenReturn(Optional.of("e2"));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e2");
    }

    @Test
    void scriptSelectorFallsBackWhenResultUnknown() throws Exception {
        TrTableDefinition d = def("t1", TrStrategyType.SCRIPT, false, "ACTIVE");
        d.setSelectorScript("file:/x.groovy");
        registry = TrTableRegistry.load(List.of(d));
        service = new TrResolveService(mockRedisWith(t), keys, registry, scripts, engine, backfill,
                mapper, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), TrMetrics.noop());
        seed("t1", "e1");
        TrCompiledScript selector = mock(TrCompiledScript.class);
        when(scripts.find("t1:selector")).thenReturn(Optional.of(selector));
        when(engine.evalSelector(any(), anyMap(), anyList(), anyLong())).thenReturn(Optional.empty());
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void scriptSelectorExceptionDegradesToFallback() throws Exception {
        TrTableDefinition d = def("t1", TrStrategyType.SCRIPT, false, "ACTIVE");
        d.setSelectorScript("file:/x.groovy");
        registry = TrTableRegistry.load(List.of(d));
        service = new TrResolveService(mockRedisWith(t), keys, registry, scripts, engine, backfill,
                mapper, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), TrMetrics.noop());
        seed("t1", "e1");
        TrCompiledScript selector = mock(TrCompiledScript.class);
        when(scripts.find("t1:selector")).thenReturn(Optional.of(selector));
        when(engine.evalSelector(any(), anyMap(), anyList(), anyLong()))
                .thenThrow(new TrScriptExecutionException("t1:selector", "eval", new RuntimeException("x")));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void redisFailureYieldsEmptyQuietly() {
        when(t.hasKey(anyString())).thenThrow(new RecoverableDataAccessException("down"));
        TrResolveResponse r = service.resolve(req("t1", "s1", null), null);
        assertThat(r.getEntryId()).isNull();
        assertThat(r.getReasons()).isEmpty();
    }

    @Test
    void resolveLogFailureIsSwallowed() throws Exception {
        seed("t1", "e1");
        when(t.opsForList().rightPush(anyString(), any()))
                .thenThrow(new RecoverableDataAccessException("log down"));
        TrResolveResponse r = service.resolve(req("t1", null, null), null);
        assertThat(r.getEntryId()).isEqualTo("e1");
    }

    @Test
    void nullCapacityBindsWithDefaultGuards() throws Exception {
        seed("t1", "e1");
        List<Object[]> captured = new ArrayList<>();
        doAnswer(inv -> {
            captured.add(inv.getArguments());
            return List.of("NEW", "e1");
        }).when(t).execute(same(TrLua.RESOLVE_BIND), anyList(), any(Object[].class));
        service.resolve(req("t1", null, null), null);
        Object[] args = captured.get(0);
        // 无 capacity → maxConc/limit 全 "-1"，窗口默认 1000
        assertThat(args[8]).isEqualTo("-1");
        assertThat(args[9]).isEqualTo("-1");
        assertThat(args[10]).isEqualTo("1000");
        assertThat(args[15]).isEqualTo("0"); // 亲和未开（session null）
    }

    @Test
    void tokenCapacityBindsUnitBucket() throws Exception {
        seed("t1", "e1");
        entryStore.put("e1", entryJson("e1", null, null,
                Map.of("max_concurrency", 3, "rate_limit_value", 2.5,
                        "rate_window_ms", 5000, "rate_unit", "TOKEN")));
        List<Object[]> captured = new ArrayList<>();
        doAnswer(inv -> {
            captured.add(inv.getArguments());
            return List.of("NEW", "e1");
        }).when(t).execute(same(TrLua.RESOLVE_BIND), anyList(), any(Object[].class));
        service.resolve(req("t1", "s1", null), null);
        Object[] args = captured.get(0);
        assertThat(args[8]).isEqualTo("3");
        assertThat(args[9]).isEqualTo("-1");  // TOKEN 口径不占 req 桶
        assertThat(args[11]).isEqualTo("2.5");
        assertThat(args[12]).isEqualTo("5000");
        assertThat(args[15]).isEqualTo("1");  // 亲和绑定
    }
}
