package fun.commons.tokenroute.admin;

import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrFeedBackfill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态重置服务分支（TR-ADM-005）：RESET/NOOP/OFFLINE/NO_ENTRY 矩阵、
 * 冷 Redis 回源重试、Lua 键位（entry/fail/win/log:state）与幂等语义。
 */
class TrStateAdminServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private final TrKeySpace keys = new TrKeySpace("tr");
    private StringRedisTemplate t;
    private TrFeedBackfill backfill;
    private TrStateAdminService service;

    @BeforeEach
    void setUp() {
        TrRedis redis = mock(TrRedis.class);
        t = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.stringTemplate()).thenReturn(t);
        backfill = mock(TrFeedBackfill.class);

        TrTableDefinition d = new TrTableDefinition();
        d.setName("t1");
        d.setRefreshUrl("http://feed.example/t1");
        service = new TrStateAdminService(redis, keys,
                TrTableRegistry.load(List.of(d)), backfill, CLOCK);
    }

    private void stubLua(Object... verdict) {
        when(t.execute(any(TrLua.STATE_RESET.getClass()), anyList(), eq("1700000000000")))
                .thenReturn(List.of(verdict));
    }

    @Test
    void unknownTableRejected() {
        assertThat(catchThrowableOfType(() -> service.reset("ghost", "e1", "ip"), ApiException.class))
                .isNotNull();
        verify(t, never()).execute(any(TrLua.STATE_RESET.getClass()), anyList(), eq("1700000000000"));
    }

    @Test
    void frozenEntryResetsAndEchoesPreviousStatus() {
        stubLua("RESET", "FROZEN");
        var out = service.reset("t1", "e1", "10.0.0.1");
        assertThat(out.get("reset")).isEqualTo(true);
        assertThat(out.get("previous_status")).isEqualTo("FROZEN");
        assertThat(out.get("entry_id")).isEqualTo("e1");
    }

    @Test
    void luaReceivesEntryFailWinLogKeys() {
        stubLua("RESET", "DEGRADED_L2");
        service.reset("t1", "e1", "10.0.0.1");
        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        verify(t).execute(any(TrLua.STATE_RESET.getClass()), keyCaptor.capture(), eq("1700000000000"));
        assertThat(keyCaptor.getValue()).containsExactly(
                keys.entry("t1", "e1"), keys.fail("e1"), keys.win("e1"), keys.logState("e1"));
    }

    @Test
    void offlineIsFeedManagedAndRejected() {
        stubLua("SKIP", "OFFLINE");
        ApiException ex = catchThrowableOfType(() -> service.reset("t1", "e1", "ip"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo(10100);
        assertThat(ex.getMessage()).contains("OFFLINE");
    }

    @Test
    void noopWhenActiveWithoutResidualFailures() {
        stubLua("NOOP", "ACTIVE");
        var out = service.reset("t1", "e1", "ip");
        assertThat(out.get("reset")).isEqualTo(false);
        assertThat(out.get("previous_status")).isEqualTo("ACTIVE");
    }

    @Test
    void missingEntryRetriedAfterBackfillThenRejected() {
        when(t.execute(any(TrLua.STATE_RESET.getClass()), anyList(), eq("1700000000000")))
                .thenReturn(List.of("SKIP", "NO_ENTRY"));
        when(backfill.pull("t1")).thenReturn(true);
        assertThat(catchThrowableOfType(() -> service.reset("t1", "e1", "ip"), ApiException.class))
                .isNotNull();
        // 冷 Redis：回源一次后重试（两跳）
        verify(t, times(2)).execute(any(TrLua.STATE_RESET.getClass()), anyList(), eq("1700000000000"));
    }

    @Test
    void missingEntryRejectedWithoutBackfillSuccess() {
        when(t.execute(any(TrLua.STATE_RESET.getClass()), anyList(), eq("1700000000000")))
                .thenReturn(List.of("SKIP", "NO_ENTRY"));
        when(backfill.pull("t1")).thenReturn(false);
        ApiException ex = catchThrowableOfType(() -> service.reset("t1", "e1", "ip"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo(10400);
    }
}
