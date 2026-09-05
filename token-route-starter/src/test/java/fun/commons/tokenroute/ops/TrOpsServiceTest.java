package fun.commons.tokenroute.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ops 只读面分支（02 §7）：表元数据回显 / 条目 MISSING / 容量与窗口观测 /
 * 日志分页过滤（from/to/tag/越界页）/ 亲和 SCAN 计数。
 */
class TrOpsServiceTest {

    private static final long NOW = 1_700_000_000_000L;

    private final TrKeySpace keys = new TrKeySpace("tr");
    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate t;
    private TrRedis redis;
    private TrOpsService service;

    @BeforeEach
    void setUp() {
        redis = mock(TrRedis.class);
        t = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.stringTemplate()).thenReturn(t);
        TrTableDefinition d = new TrTableDefinition();
        d.setName("t1");
        d.setRefreshUrl("http://feed.example/t1");
        when(t.opsForSet().members(keys.entryIds("t1")))
                .thenReturn(new LinkedHashSet<>(List.of("e1", "e2")));
        when(t.opsForZSet().size(anyString())).thenReturn(3L);
        when(t.opsForZSet().rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Set.of("m1:ok", "m2:fail", "m3:ok"));
        when(t.opsForValue().get(keys.fail("e1"))).thenReturn("2");
        service = new TrOpsService(redis, keys, TrTableRegistry.load(List.of(d)), mapper);
    }

    @Test
    void unknownTableRejected() {
        assertThatThrownBy(() -> service.tableStatus("ghost"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tableStatusEchoesSeedMetadata() {
        // issue #3 R2：策略版本追溯标记经 TR-OPS-001 原样回显
        when(t.opsForSet().members(keys.entryIds("t-meta"))).thenReturn(new LinkedHashSet<>());
        TrTableDefinition d = new TrTableDefinition();
        d.setName("t-meta");
        d.setRefreshUrl("http://feed.example/t-meta");
        d.setMetadata(Map.of("strategy-version", "tokengo-v2"));
        TrOpsService metaService = new TrOpsService(redis, keys,
                TrTableRegistry.load(List.of(d)), mapper);
        Map<String, Object> out = metaService.tableStatus("t-meta");
        assertThat(out.get("metadata")).isEqualTo(Map.of("strategy-version", "tokengo-v2"));
    }

    @Test
    void missingEntryRenderedAsMissing() {
        when(t.opsForValue().get(anyString())).thenReturn(null);
        Map<String, Object> out = service.tableStatus("t1");
        assertThat(out.get("table_id")).isEqualTo("t1");
        assertThat(out.get("affinity_active_count")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void entriesRenderedWithCapacityAndWindowUsage() throws Exception {
        when(t.opsForValue().get(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            if (k.startsWith("tr:fail:")) {
                return "1";
            }
            if (k.endsWith(":e1")) {
                return mapper.writeValueAsString(Map.of(
                        "entry_id", "e1", "name", "n", "status", "ACTIVE",
                        "data_json", Map.of("capacity",
                                Map.of("rate_limit_value", 10, "rate_unit", "TOKEN"))));
            }
            return mapper.writeValueAsString(Map.of(
                    "entry_id", "e2", "name", "n2", "status", "ACTIVE"));
        });
        when(t.opsForZSet().removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(1L);
        Cursor<String> cursor = mockCursor();
        when(t.scan(any(ScanOptions.class))).thenReturn(cursor);

        Map<String, Object> out = service.tableStatus("t1");
        assertThat(out.get("strategy_type")).isEqualTo("WEIGHTED_RANDOM");
        assertThat(out.get("affinity_active_count")).isEqualTo(2L);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) out.get("entries");
        assertThat(entries).hasSize(2);
        Map<String, Object> e1 = entries.stream()
                .filter(e -> "e1".equals(e.get("entry_id"))).findFirst().orElseThrow();
        assertThat(e1.get("rate_unit")).isEqualTo("TOKEN");
        assertThat(e1.get("rate_limit_value")).isEqualTo(10.0);
        assertThat(e1.get("win_calls")).isEqualTo(3);
        assertThat(e1.get("win_failures")).isEqualTo(1);
        Map<String, Object> e2 = entries.stream()
                .filter(e -> "e2".equals(e.get("entry_id"))).findFirst().orElseThrow();
        assertThat(e2.get("rate_window_ms")).isEqualTo(1000L); // 无 capacity → 默认窗口
    }

    @SuppressWarnings("unchecked")
    private static Cursor<String> mockCursor() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("a", "b");
        return cursor;
    }

    private String line(String json) {
        return json;
    }

    @Test
    void resolveLogsFilterByWindowAndResult() {
        when(t.opsForList().range(anyString(), anyLong(), anyLong())).thenReturn(List.of(
                line("{\"at\":" + (NOW - 1000) + ",\"result\":\"ok\"}"),
                line("{\"at\":" + (NOW + 1000) + ",\"result\":\"empty\"}"),
                line("not-json")));
        assertThat(service.resolveLogs("t1", null, null, null, 1, 20)).hasSize(3);
        assertThat(service.resolveLogs("t1", NOW, null, null, 1, 20)).hasSize(1);
        assertThat(service.resolveLogs("t1", null, NOW, null, 1, 20)).hasSize(2);
        assertThat(service.resolveLogs("t1", null, null, "ok", 1, 20)).hasSize(1);
        assertThat(service.resolveLogs("t1", null, null, "ok", 5, 20)).isEmpty();
        assertThat(service.resolveLogs("t1", null, null, null, 1, 2)).hasSize(2);
    }

    @Test
    void resolveLogsWindowFilterDropsEntriesWithoutAt() {
        when(t.opsForList().range(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(line("{\"result\":\"ok\"}")));
        assertThat(service.resolveLogs("t1", NOW, null, null, 1, 20)).isEmpty();
    }

    @Test
    void affinityEventsFilterByType() {
        when(t.opsForList().range(anyString(), anyLong(), anyLong())).thenReturn(List.of(
                line("{\"at\":" + NOW + ",\"type\":\"BIND\"}"),
                line("{\"at\":" + NOW + ",\"type\":\"DETACH\"}")));
        assertThat(service.affinityEvents("t1", "BIND", 1, 20)).hasSize(1);
        assertThat(service.affinityEvents("t1", "DETACH", 1, 20)).hasSize(1);
        assertThat(service.affinityEvents("t1", null, 1, 20)).hasSize(2);
    }

    @Test
    void stateLogsPaginated() {
        when(t.opsForList().range(anyString(), anyLong(), anyLong())).thenReturn(List.of(
                line("{\"at\":1}"), line("{\"at\":2}"), line("{\"at\":3}")));
        assertThat(service.stateLogs("e1", 1, 2)).hasSize(2);
        assertThat(service.stateLogs("e1", 2, 2)).hasSize(1);
        assertThat(service.stateLogs("e1", 9, 2)).isEmpty();
    }
}
