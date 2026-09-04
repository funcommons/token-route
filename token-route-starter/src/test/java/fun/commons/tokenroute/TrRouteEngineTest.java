package fun.commons.tokenroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.report.TrReportRequest;
import fun.commons.tokenroute.report.TrReportResponse;
import fun.commons.tokenroute.report.TrReportService;
import fun.commons.tokenroute.resolve.TrResolveRequest;
import fun.commons.tokenroute.resolve.TrResolveResponse;
import fun.commons.tokenroute.resolve.TrResolveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 进程内门面分支（03_嵌入式SDK接入指南）：resolve/report 委托 + caller 归因透传；
 * detach 内核（TR-CTR-003）：DEL + DETACH 事件（by=CONSUMER）、幂等、session 必填。
 */
class TrRouteEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private final TrKeySpace keys = new TrKeySpace("tr");
    private final ObjectMapper mapper = new ObjectMapper();
    private TrResolveService resolveService;
    private TrReportService reportService;
    private StringRedisTemplate t;
    private TrRouteEngine engine;

    @BeforeEach
    void setUp() {
        resolveService = mock(TrResolveService.class);
        reportService = mock(TrReportService.class);
        TrRedis redis = mock(TrRedis.class);
        t = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.stringTemplate()).thenReturn(t);
        engine = new TrRouteEngine(resolveService, reportService, redis, keys, mapper, CLOCK);
        when(t.opsForList().rightPush(anyString(), anyString())).thenReturn(1L);
    }

    @Test
    void resolveDelegatesWithCallerId() {
        TrResolveResponse data = new TrResolveResponse();
        data.setEntryId("e-1");
        when(resolveService.resolve(any(TrResolveRequest.class), eq("host-svc"))).thenReturn(data);

        TrResolveResponse out = engine.resolve("t1", "s1", Map.of("model", "gpt-4o"), "host-svc");
        assertThat(out.getEntryId()).isEqualTo("e-1");

        ArgumentCaptor<TrResolveRequest> captor = ArgumentCaptor.forClass(TrResolveRequest.class);
        verify(resolveService).resolve(captor.capture(), eq("host-svc"));
        assertThat(captor.getValue().getTableId()).isEqualTo("t1");
        assertThat(captor.getValue().getSessionId()).isEqualTo("s1");
        assertThat(captor.getValue().getBizParams()).containsEntry("model", "gpt-4o");
    }

    @Test
    void resolveRequestDelegatesWithNullCaller() {
        TrResolveRequest request = new TrResolveRequest();
        request.setTableId("t1");
        TrResolveResponse data = new TrResolveResponse();
        when(resolveService.resolve(same(request), isNull())).thenReturn(data);
        assertThat(engine.resolve(request)).isSameAs(data);
    }

    @Test
    void reportDelegates() {
        TrReportRequest request = new TrReportRequest();
        TrReportResponse data = new TrReportResponse();
        when(reportService.report(same(request))).thenReturn(data);
        assertThat(engine.report(request)).isSameAs(data);
    }

    @Test
    void detachDeletesBindingAndWritesConsumerEvent() {
        when(t.opsForValue().get(keys.affinity("t1", "s1"))).thenReturn("e-1");
        when(t.delete(anyString())).thenReturn(true);

        assertThat(engine.detach("t1", "s1")).isTrue();
        verify(t).delete(keys.affinity("t1", "s1"));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(t.opsForList()).rightPush(eq(keys.logAff("t1")), json.capture());
        assertThat(json.getValue()).contains("\"type\":\"DETACH\"").contains("CONSUMER")
                .contains("\"session\":\"s1\"").contains("\"entry_id\":\"e-1\"");
    }

    @Test
    void detachWithoutBindingIsIdempotentFalse() {
        when(t.delete(anyString())).thenReturn(false);
        assertThat(engine.detach("t1", "s-none")).isFalse();
        verify(t.opsForList(), never()).rightPush(anyString(), anyString()); // 未删成功不落事件
    }

    @Test
    void detachRequiresSessionId() {
        assertThat(catchThrowableOfType(() -> engine.detach("t1", " "), IllegalArgumentException.class))
                .isNotNull();
        assertThat(catchThrowableOfType(() -> engine.detach("t1", null), IllegalArgumentException.class))
                .isNotNull();
    }
}
