package fun.commons.tokenroute.report;

import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.common.TrCode;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * report 三态分支（01 §5.3）：批量校验 / 逐条校验四拒因 / 表反查 / 未知条目 /
 * L3 REJECTED / FROZEN 信号（DISABLE_FAIL 与状态机两路）/ 存储故障补发拒收 / TOKEN-BIT-REQUEST 口径。
 */
class TrReportServiceBranchTest {

    private static final long NOW = 1_700_000_000_000L;

    private final TrKeySpace keys = new TrKeySpace("tr");
    private StringRedisTemplate t;
    @SuppressWarnings("unchecked")
    private final org.springframework.data.redis.core.SetOperations<String, String> setOps =
            mock(org.springframework.data.redis.core.SetOperations.class);
    private TrReportService service;

    @BeforeEach
    void setUp() {
        TrRedis redis = mock(TrRedis.class);
        t = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.stringTemplate()).thenReturn(t);
        when(t.opsForSet()).thenReturn(setOps);
        TrTableDefinition d1 = new TrTableDefinition();
        d1.setName("t1");
        d1.setRefreshUrl("http://feed.example/t1");
        TrTableDefinition d2 = new TrTableDefinition();
        d2.setName("t2");
        d2.setRefreshUrl("http://feed.example/t2");
        when(setOps.isMember(eq(keys.entryIds("t1")), anyString())).thenReturn(false);
        when(setOps.isMember(eq(keys.entryIds("t2")), anyString())).thenReturn(true);
        when(t.opsForValue().get(anyString())).thenReturn(
                "{\"entry_id\":\"e1\",\"name\":\"n\",\"data_json\":{\"capacity\":{\"rate_unit\":\"TOKEN\"}}}");
        doReturn(List.of("OK", ""))
                .when(t).execute(same(TrLua.REPORT_AGGREGATE), anyList(), any(Object[].class));
        service = new TrReportService(redis, keys, TrTableRegistry.load(List.of(d1, d2)),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    private static TrReportRequest.Item item(String entryId, String leaseId, String result, Double units) {
        TrReportRequest.Item i = new TrReportRequest.Item();
        i.setEntryId(entryId);
        i.setLeaseId(leaseId);
        i.setResult(result);
        i.setRateUnits(units);
        return i;
    }

    private static TrReportRequest batch(TrReportRequest.Item... items) {
        TrReportRequest r = new TrReportRequest();
        r.setReports(List.of(items));
        return r;
    }

    @Test
    void batchBoundariesEnforced() {
        assertThatThrownBy(() -> service.report(batch())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.report(new TrReportRequest())).isInstanceOf(ApiException.class);
        TrReportRequest.Item[] tooMany = IntStream.rangeClosed(1, TrReportRequest.MAX_BATCH + 1)
                .mapToObj(i -> item("e1", "l", "SUCCESS", 1.0))
                .toArray(TrReportRequest.Item[]::new);
        assertThatThrownBy(() -> service.report(batch(tooMany))).isInstanceOf(ApiException.class);
    }

    @Test
    void itemValidationRejectsAllFourWays() {
        TrReportResponse out = service.report(batch(
                item(" ", "l", "SUCCESS", 1.0),
                item("e1", " ", "SUCCESS", 1.0),
                item("e1", "l", "NOPE", 1.0),
                item("e1", "l", "SUCCESS", -0.5)));
        assertThat(out.getAccepted()).isZero();
        assertThat(out.getRejected()).hasSize(4);
        assertThat(out.getRejected()).allSatisfy(r ->
                assertThat(r.getCode()).isEqualTo(TrCode.PARAM_ERROR.getCode()));
    }

    @Test
    void unknownEntryRejectedWithNotFound() {
        when(setOps.isMember(anyString(), anyString())).thenReturn(false);
        TrReportResponse out = service.report(batch(item("ghost", "l", "SUCCESS", 1.0)));
        assertThat(out.getRejected()).hasSize(1);
        assertThat(out.getRejected().get(0).getCode()).isEqualTo(TrCode.NOT_FOUND.getCode());
        assertThat(out.getRejected().get(0).getMessage()).contains("未知");
    }

    @Test
    void entryJsonMissingRejectedAsUnknown() {
        when(setOps.isMember(eq(keys.entryIds("t2")), anyString())).thenReturn(true);
        when(t.opsForValue().get(anyString())).thenReturn(null);
        TrReportResponse out = service.report(batch(item("e1", "l", "SUCCESS", 1.0)));
        assertThat(out.getRejected()).hasSize(1);
        assertThat(out.getRejected().get(0).getMessage()).contains("未知");
    }

    @Test
    void firstMatchingTableWins() {
        // e1 属于 t2（第二张表）→ 覆盖逐表反查 continue 分支
        TrReportResponse out = service.report(batch(item("e1", "l", "SUCCESS", 1.0)));
        assertThat(out.getAccepted()).isEqualTo(1);
    }

    @Test
    void luaRejectedMeansIllegalLease() {
        doReturn(List.of("REJECTED")).when(t).execute(same(TrLua.REPORT_AGGREGATE), anyList(), any(Object[].class));
        TrReportResponse out = service.report(batch(item("e1", "bad-lease", "SUCCESS", 1.0)));
        assertThat(out.getRejected()).hasSize(1);
        assertThat(out.getRejected().get(0).getMessage()).contains("lease");
    }

    @Test
    void frozenSignalLoggedForBothPaths() {
        doReturn(List.of("OK", "FROZEN"), List.of("OK", "FROZEN"))
                .when(t).execute(same(TrLua.REPORT_AGGREGATE), anyList(), any(Object[].class));
        TrReportResponse out = service.report(batch(
                item("e1", "l", "DISABLE_FAIL", 1.0),   // REPORT_DISABLE 路径
                item("e1", "l", "RETRYABLE_FAIL", 1.0))); // STATE_MACHINE 路径
        assertThat(out.getAccepted()).isEqualTo(2);
    }

    @Test
    void redisOutageRejectsWithReplayHint() {
        doThrow(new RecoverableDataAccessException("down"))
                .when(t).execute(same(TrLua.REPORT_AGGREGATE), anyList(), any(Object[].class));
        TrReportResponse out = service.report(batch(item("e1", "l", "SUCCESS", 1.0)));
        assertThat(out.getRejected()).hasSize(1);
        assertThat(out.getRejected().get(0).getMessage()).contains("补发");
    }

    @Test
    void capacityUnitVariantsAllAccepted() {
        when(t.opsForValue().get(anyString())).thenReturn(
                "{\"entry_id\":\"e1\",\"data_json\":{\"capacity\":{\"rate_limit_value\":10,\"rate_unit\":\"BIT\"}}}");
        assertThat(service.report(batch(item("e1", "l", "SUCCESS", 0.5))).getAccepted()).isEqualTo(1);

        when(t.opsForValue().get(anyString())).thenReturn(
                "{\"entry_id\":\"e1\",\"data_json\":{\"capacity\":{\"rate_limit_value\":10}}}"); // REQUEST 口径
        assertThat(service.report(batch(item("e1", "l", "SUCCESS", 1.0))).getAccepted()).isEqualTo(1);

        when(t.opsForValue().get(anyString())).thenReturn("{\"entry_id\":\"e1\"}"); // 无 capacity
        assertThat(service.report(batch(item("e1", "l", "SUCCESS", null))).getAccepted()).isEqualTo(1);
    }
}
