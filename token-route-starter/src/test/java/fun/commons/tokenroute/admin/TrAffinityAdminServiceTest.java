package fun.commons.tokenroute.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrFeedBackfill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 亲和管理服务分支（issue #1 验收：set/upsert/TTL、get、list 前缀、delete）：
 * 表/条目校验拒绝、缺省/自定义 TTL、改绑回显、事件 ring（by=ADMIN_*）、SCAN 截断、幂等删除。
 */
class TrAffinityAdminServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private final TrKeySpace keys = new TrKeySpace("tr");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> stringValues = new HashMap<>();
    private final Set<String> knownEntries = new HashSet<>();
    private StringRedisTemplate t;
    private TrFeedBackfill backfill;
    private TrAffinityAdminService service;

    @BeforeEach
    void setUp() {
        TrRedis redis = mock(TrRedis.class);
        t = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.stringTemplate()).thenReturn(t);
        backfill = mock(TrFeedBackfill.class);

        TrTableDefinition d = new TrTableDefinition();
        d.setName("t1");
        d.setRefreshUrl("http://feed.example/t1");
        TrTableDefinition disabled = new TrTableDefinition();
        disabled.setName("t-off");
        disabled.setRefreshUrl("http://feed.example/t-off");
        disabled.setAffinityEnabled(false);

        service = new TrAffinityAdminService(redis, keys,
                TrTableRegistry.load(List.of(d, disabled)), backfill, mapper, CLOCK);

        // 集中可控 GET 面：条目键 + 亲和键的返回值均由本 map 直接驱动
        knownEntries.add(keys.entry("t1", "e1"));
        stringValues.put(keys.entry("t1", "e1"), "{\"entry_id\":\"e1\",\"status\":\"ACTIVE\"}");
        stringValues.put(keys.affinity("t1", "s1"), "e-old");
        when(t.opsForValue().get(anyString()))
                .thenAnswer(inv -> stringValues.get(inv.<String>getArgument(0)));
        when(t.getExpire(anyString(), any(TimeUnit.class))).thenReturn(42L);
        when(t.opsForList().rightPush(anyString(), anyString())).thenReturn(1L);
    }

    // —— set ——

    @Test
    void setUnknownTableRejected() {
        ApiException ex = catchThrowableOfType(
                () -> service.set("ghost", "s1", "e1", null, "10.0.0.1"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo(10400);
    }

    @Test
    void setOnAffinityDisabledTableRejected() {
        ApiException ex = catchThrowableOfType(
                () -> service.set("t-off", "s1", "e1", null, "10.0.0.1"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo(10100);
        assertThat(ex.getMessage()).contains("未开启亲和");
    }

    @Test
    void setUnknownEntryRejectedEvenAfterBackfill() {
        when(backfill.pull("t1")).thenReturn(false);
        ApiException ex = catchThrowableOfType(
                () -> service.set("t1", "s2", "e-missing", null, "10.0.0.1"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo(10400);
    }

    @Test
    void setUnknownEntryFoundAfterBackfillRetry() {
        when(backfill.pull("t1")).thenAnswer(inv -> {
            // 回源后条目出现（冷 Redis 场景）
            stringValues.put(keys.entry("t1", "e2"), "{\"entry_id\":\"e2\"}");
            return true;
        });
        Map<String, Object> out = service.set("t1", "s2", "e2", null, "10.0.0.1");
        assertThat(out.get("entry_id")).isEqualTo("e2");
    }

    @Test
    void setUpsertsWithTableDefaultTtlAndEchoesPrevious() {
        Map<String, Object> out = service.set("t1", "s1", "e1", null, "10.0.0.1");
        assertThat(out.get("ttl_seconds")).isEqualTo(28800); // 表级 affinity_idle_timeout_seconds 缺省
        assertThat(out.get("previous_entry_id")).isEqualTo("e-old");
        verify(t.opsForValue()).set(eq(keys.affinity("t1", "s1")), eq("e1"), eq(Duration.ofSeconds(28800)));
    }

    @Test
    void setCustomTtlHonored() {
        service.set("t1", "s2", "e1", 60, "10.0.0.1");
        verify(t.opsForValue()).set(eq(keys.affinity("t1", "s2")), eq("e1"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void setTtlOutOfRangeRejected() {
        assertThat(catchThrowableOfType(() -> service.set("t1", "s2", "e1", 0, "ip"), ApiException.class))
                .isNotNull();
        assertThat(catchThrowableOfType(() -> service.set("t1", "s2", "e1", 604_801, "ip"), ApiException.class))
                .isNotNull();
    }

    @Test
    void setWritesBindEventWithAdminSource() {
        service.set("t1", "s2", "e1", null, "10.0.0.1");
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(t.opsForList()).rightPush(eq(keys.logAff("t1")), json.capture());
        assertThat(json.getValue()).contains("\"type\":\"BIND\"").contains("ADMIN_SET")
                .contains("\"session\":\"s2\"").contains("\"entry_id\":\"e1\"");
        verify(t.opsForList()).trim(eq(keys.logAff("t1")), eq(-1000L), eq(-1L));
    }

    @Test
    void setSessionTooLongRejected() {
        assertThat(catchThrowableOfType(
                () -> service.set("t1", "s".repeat(129), "e1", null, "ip"), ApiException.class))
                .isNotNull();
    }

    // —— get ——

    @Test
    void getReturnsEntryAndRemainingTtl() {
        Map<String, Object> out = service.get("t1", "s1");
        assertThat(out.get("found")).isEqualTo(true);
        assertThat(out.get("entry_id")).isEqualTo("e-old");
        assertThat(out.get("ttl_remaining_seconds")).isEqualTo(42L);
    }

    @Test
    void getMissingReturnsFoundFalse() {
        assertThat(service.get("t1", "s-none")).containsOnlyKeys("found").containsEntry("found", false);
    }

    // —— list ——

    @Test
    @SuppressWarnings("unchecked")
    void listScansPrefixAndReportsCompleteWhenUnderCap() {
        stubScanCursor(keys.affinity("t1", "s-a"), keys.affinity("t1", "s-b"));
        Map<String, Object> out = service.list("t1", "s-", null);
        assertThat(out.get("complete")).isEqualTo(true);
        assertThat(out.get("count")).isEqualTo(2);
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        // session_id 还原（剥键前缀）+ 按 session 排序
        assertThat(items).extracting(m -> m.get("session_id")).containsExactly("s-a", "s-b");
        assertThat(items.get(0)).containsEntry("entry_id", "e1").containsEntry("ttl_remaining_seconds", 42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listOverCapMarksIncomplete() {
        stubScanCursor(keys.affinity("t1", "s-a"), keys.affinity("t1", "s-b"), keys.affinity("t1", "s-c"));
        Map<String, Object> out = service.list("t1", "s-", 2);
        assertThat(out.get("complete")).isEqualTo(false);
        assertThat(out.get("count")).isEqualTo(2);
        assertThat((List<Map<String, Object>>) out.get("items")).hasSize(2);
    }

    @Test
    void listSkipsKeysExpiringBetweenScanAndGet() {
        stubScanCursor(keys.affinity("t1", "s-gone"), keys.affinity("t1", "s-live"));
        stringValues.remove(keys.affinity("t1", "s-gone")); // SCAN 与 GET 间过期
        Map<String, Object> out = service.list("t1", "s-", null);
        assertThat(out.get("count")).isEqualTo(1);
        assertThat(out.get("complete")).isEqualTo(true);
    }

    @Test
    void listClampsLimit() {
        stubScanCursor(keys.affinity("t1", "s-a"));
        Map<String, Object> out = service.list("t1", null, 9999);
        assertThat(out.get("count")).isEqualTo(1); // limit 钳到上限不越界
    }

    // —— delete ——

    @Test
    void deleteExistingWritesDetachEventAndEchoesEntry() {
        when(t.delete(anyString())).thenReturn(true);
        Map<String, Object> out = service.delete("t1", "s1", "10.0.0.1");
        assertThat(out.get("deleted")).isEqualTo(true);
        assertThat(out.get("entry_id")).isEqualTo("e-old");
        verify(t.opsForList()).rightPush(eq(keys.logAff("t1")), contains("ADMIN_DELETE"));
    }

    @Test
    void deleteAbsentIsIdempotentFalse() {
        when(t.delete(anyString())).thenReturn(false);
        Map<String, Object> out = service.delete("t1", "s-none", "10.0.0.1");
        assertThat(out.get("deleted")).isEqualTo(false);
        assertThat(out.get("entry_id")).isNull();
        verify(t.opsForList(), never()).rightPush(anyString(), anyString()); // 未删成功不落 DETACH 事件
    }

    // —— 工具 ——

    @SuppressWarnings("unchecked")
    private void stubScanCursor(String... scannedKeys) {
        Cursor<String> cursor = mock(Cursor.class);
        Boolean[] seq = new Boolean[scannedKeys.length + 1]; // [true…true, false]
        java.util.Arrays.fill(seq, true);
        seq[seq.length - 1] = false;
        when(cursor.hasNext()).thenReturn(seq[0], java.util.Arrays.copyOfRange(seq, 1, seq.length));
        when(cursor.next()).thenReturn(scannedKeys[0],
                java.util.Arrays.copyOfRange(scannedKeys, 1, scannedKeys.length));
        when(t.scan(any(ScanOptions.class))).thenReturn(cursor);
        // SCAN 出的亲和键默认均有值（除 s1 保持 e-old；单测可覆写/移除）
        for (String k : scannedKeys) {
            stringValues.putIfAbsent(k, "e1");
        }
    }
}
