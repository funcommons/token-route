package fun.commons.tokenroute.feed;

import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.observe.TrMetrics;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FEED 拉取分支（01 §5.5）：上游 200/非 200/网络拒绝、10633 schema 各拒绝分支、
 * 超限拒收、惰性判定（last_pull 新/旧/缺失）、连败告警阈值、失败保旧值。
 * 上游用 JDK HttpServer 进程内真实 HTTP；Redis 数据面 mock。
 */
class TrFeedRefreshServiceTest {

    private static final long NOW = 1_700_000_000_000L;

    private static HttpServer server;
    private static int port;
    private static int refusedPort;

    private TrFeedRefreshService service;
    private StringRedisTemplate t;

    @BeforeAll
    static void startFeed() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().equals("/n500")) {
                respond(ex, 500, "upstream error");
            } else {
                respond(ex, 200, bodyFor(ex.getRequestURI().getPath()));
            }
        });
        server.start();

        try (ServerSocket s = new ServerSocket(0)) {   // 拿一个确定无人监听的端口
            refusedPort = s.getLocalPort();
        }
    }

    @AfterAll
    static void stopFeed() {
        server.stop(0);
    }

    private static String bodyFor(String path) {
        return switch (path) {
            case "/good" -> "{\"entries\":[{\"name\":\"a\",\"weight\":2,\"order_no\":1," +
                    "\"status\":\"ACTIVE\",\"data_json\":{\"k\":\"v\"}},{\"name\":\"b\",\"data_json\":\"scalar\"}]}";
            case "/noentries" -> "{\"foo\":1}";
            case "/badelem" -> "{\"entries\":[1]}";
            case "/blankname" -> "{\"entries\":[{\"name\":\" \"}]}";
            case "/badstatus" -> "{\"entries\":[{\"name\":\"a\",\"status\":\"PAUSED\"}]}";
            case "/oversize" -> "{\"entries\":[{\"name\":\"a\",\"data_json\":{\"pad\":\"" +
                    "x".repeat(9000) + "\"}}]}";
            case "/badjson" -> "{not json";
            default -> "whatever";
        };
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @BeforeEach
    void setUp() {
        TrRedis redis = mock(TrRedis.class);
        t = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.stringTemplate()).thenReturn(t);
        when(t.opsForHash().increment(anyString(), anyString(), anyLong())).thenReturn(1L);
        Mockito.doReturn(List.of(2, 0, 0))
                .when(t).execute(same(TrLua.ENTRY_UPSERT), anyList(), any(Object[].class));

        List<TrTableDefinition> defs = new ArrayList<>();
        defs.add(table("t-good", "http://127.0.0.1:" + port + "/good"));
        defs.add(table("t-500", "http://127.0.0.1:" + port + "/n500"));
        defs.add(table("t-badjson", "http://127.0.0.1:" + port + "/badjson"));
        defs.add(table("t-noentries", "http://127.0.0.1:" + port + "/noentries"));
        defs.add(table("t-badelem", "http://127.0.0.1:" + port + "/badelem"));
        defs.add(table("t-blankname", "http://127.0.0.1:" + port + "/blankname"));
        defs.add(table("t-badstatus", "http://127.0.0.1:" + port + "/badstatus"));
        defs.add(table("t-oversize", "http://127.0.0.1:" + port + "/oversize"));
        defs.add(table("t-refused", "http://127.0.0.1:" + refusedPort + "/x"));
        service = new TrFeedRefreshService(redis, new TrKeySpace("tr"),
                TrTableRegistry.load(defs), new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), TrMetrics.noop());
    }

    private static TrTableDefinition table(String name, String url) {
        TrTableDefinition d = new TrTableDefinition();
        d.setName(name);
        d.setRefreshUrl(url);
        return d;
    }

    @Test
    void goodPullUpsertsAndMarksSuccess() {
        TrFeedRefreshService.Result r = service.pullNow("t-good");
        assertThat(r.success()).isTrue();
        assertThat(r.pulled()).isEqualTo(2);
        assertThat(r.upserted()).isEqualTo(2);
        assertThat(r.removed()).isZero();
        assertThat(r.error()).isNull();
    }

    @Test
    void non200FailsWithReason() {
        TrFeedRefreshService.Result r = service.pullNow("t-500");
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("非 200");
    }

    @Test
    void unreadableBodyFailsGracefully() {
        assertThat(service.pullNow("t-badjson").success()).isFalse();
    }

    @Test
    void schemaViolationsRejectedWith10633() {
        assertThat(service.pullNow("t-noentries").error()).contains("10633");
        assertThat(service.pullNow("t-badelem").error()).contains("10633");
        assertThat(service.pullNow("t-blankname").error()).contains("10633");
        assertThat(service.pullNow("t-badstatus").error()).contains("10633");
        assertThat(service.pullNow("t-oversize").error()).contains("超上限");
    }

    @Test
    void connectionRefusedFailsAndCountsFailure() {
        TrFeedRefreshService.Result r = service.pullNow("t-refused");
        assertThat(r.success()).isFalse();
        assertThat(r.error()).isNotBlank();
    }

    @Test
    void repeatedFailuresReachAlertThreshold() {
        when(t.opsForHash().increment(anyString(), anyString(), anyLong())).thenReturn(3L);
        assertThat(service.pullNow("t-500").success()).isFalse();
    }

    @Test
    void unknownTableShortCircuits() {
        TrFeedRefreshService.Result r = service.pullNow("t-nope");
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("未注册");
    }

    @Test
    void pullIfStaleSkipsFreshCursor() {
        when(t.opsForHash().get(anyString(), anyString())).thenReturn(String.valueOf(NOW - 1_000));
        assertThat(service.pullIfStale("t-good", 60)).isFalse();
    }

    @Test
    void pullIfStalePullsWhenCursorMissingOrOld() {
        when(t.opsForHash().get(anyString(), anyString())).thenReturn(null);
        assertThat(service.pullIfStale("t-good", 60)).isTrue();
        when(t.opsForHash().get(anyString(), anyString())).thenReturn(String.valueOf(NOW - 61_000));
        assertThat(service.pullIfStale("t-good", 60)).isTrue();
    }

    @Test
    void pullDelegatesToPullNow() {
        assertThat(service.pull("t-good")).isTrue();
        assertThat(service.pull("t-nope")).isFalse();
    }
}
