package fun.commons.tokenroute.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.observe.TrMetrics;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrFeedBackfill;
import fun.commons.tokenroute.resolve.TrResolveRequest;
import fun.commons.tokenroute.resolve.TrResolveResponse;
import fun.commons.tokenroute.resolve.TrResolveService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEED 三态刷新用例（issue #2 R1 验收：TokenGo 导出器对接的仓内侧证明）：
 * ① 冷启动——键不存在 → resolve 同步回源一次（3s 防击穿）；
 * ② 懒刷新——列表龄超 refresh-interval → 先服务旧值 + 后台单飞拉取，随后收敛到新值；
 * ③ 强制刷新——refresh ping（pullNow）同步拉取立即生效。
 * mock 上游为进程内 JDK HttpServer（响应体可热改，模拟 TokenGo 导出器数据变更）。
 * 需 Docker（Redis）；-Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrFeedThreeTriggerIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";
    private static final String TID = "tokengo-feed";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static StringRedisTemplate redis;
    static TrKeySpace keys;
    static TrResolveService resolve;
    static fun.commons.tokenroute.feed.TrFeedRefreshService feed;
    static HttpServer upstream;
    static final AtomicReference<String> feedBody = new AtomicReference<>();

    @BeforeAll
    static void setUp() throws Exception {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        keys = new TrKeySpace("tr");

        // mock TokenGo 导出器：GET /feed 返回可热改的全量条目
        feedBody.set(body("https://up-v1.example.com", "v1-payload"));
        upstream = HttpServer.create(new InetSocketAddress(0), 0);
        upstream.createContext("/feed", exchange -> {
            byte[] resp = feedBody.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        upstream.start();

        TrTableDefinition table = new TrTableDefinition();
        table.setName(TID);
        table.setRefreshUrl("http://localhost:" + upstream.getAddress().getPort() + "/feed");
        table.setRefreshIntervalSeconds(60);
        TrTableRegistry registry = TrTableRegistry.load(List.of(table));
        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry())
                .loadAll(registry);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrRedis trRedis = new TrRedis(manager, "main");
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.systemUTC();
        feed = new fun.commons.tokenroute.feed.TrFeedRefreshService(trRedis, keys, registry, mapper, clock,
                TrMetrics.noop());
        resolve = new TrResolveService(trRedis, keys, registry, scripts, new TrScriptEngine(500),
                feed, mapper, clock, TrMetrics.noop());
    }

    @AfterAll
    static void tearDown() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void threeRefreshTriggersAgainstMutableUpstream() throws Exception {
        // ① 冷启动：Redis 无条目键 → resolve 触发同步回源，立即拿到 v1 载荷
        TrResolveResponse cold = resolve.resolve(req(), "tokengo");
        assertThat(cold.getEntryId()).isNotBlank();
        assertThat(cold.getDataJson()).containsEntry("base_url", "https://up-v1.example.com");
        assertThat(redis.opsForSet().size(keys.entryIds(TID))).isEqualTo(1);

        // ② 懒刷新：上游改版 v2 → 未到刷新周期内 resolve 仍服务旧值（保旧值语义）；
        //    将游标龄拨老 → 下一次 resolve 服务旧值 + 后台单飞拉取 → 轮询收敛到 v2
        feedBody.set(body("https://up-v2.example.com", "v2-payload"));
        TrResolveResponse stale = resolve.resolve(req(), "tokengo");
        assertThat(stale.getDataJson()).containsEntry("base_url", "https://up-v1.example.com");
        redis.opsForHash().put(keys.feed(TID), "last_pull",
                String.valueOf(System.currentTimeMillis() - 61_000)); // 拨老游标（> 60s 周期）
        resolve.resolve(req(), "tokengo"); // 触发后台懒刷新（调用方即刻拿旧值返回）
        awaitUntil(() -> java.util.Objects.equals(
                resolve.resolve(req(), "tokengo").getDataJson().get("base_url"),
                "https://up-v2.example.com") ? 0 : 1);

        // ③ 强制刷新（refresh ping）：上游改版 v3 → 不动游标，pullNow 同步拉取立即生效
        feedBody.set(body("https://up-v3.example.com", "v3-payload"));
        assertThat(resolve.resolve(req(), "tokengo").getDataJson())
                .containsEntry("base_url", "https://up-v2.example.com"); // 游标未老 → 仍 v2
        var result = feed.pullNow(TID); // TR-CTR-004 等价（POST /v1/refresh/{tid} 同路径）
        assertThat(result.success()).isTrue();
        assertThat(resolve.resolve(req(), "tokengo").getDataJson())
                .containsEntry("base_url", "https://up-v3.example.com"); // 立即 v3
    }

    private static TrResolveRequest req() {
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId(TID);
        return r;
    }

    private static String body(String baseUrl, String marker) {
        return "{\"entries\":[{\"name\":\"tg-main\",\"weight\":100,\"order_no\":1,\"status\":\"ACTIVE\","
                + "\"data_json\":{\"base_url\":\"" + baseUrl + "\",\"marker\":\"" + marker + "\"}}]}";
    }

    /** 轮询断言：cond 返回 0 视为满足（可抛检查异常）；超时抛 AssertionError */
    private static void awaitUntil(ThrowingInt cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (cond.eval() == 0) {
                    return;
                }
            } catch (Exception e) {
                throw new AssertionError("懒刷新轮询内异常: " + e.getMessage());
            }
            Thread.sleep(200);
        }
        throw new AssertionError("懒刷新未在 10s 内收敛到新值");
    }

    private interface ThrowingInt {
        int eval() throws Exception;
    }
}
