package fun.commons.tokenroute.it;

import fun.commons.tokenroute.observe.TrMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.feed.TrFeedRefreshService;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrEntryId;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4 IT：FEED 拉取三触发 / L5 全量对比 / schema 校验 10633 保旧值 / 评估器状态机（L4）。
 * 需 Docker；-Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrFeedEvalIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";
    private static final String TID = "llm";
    private static final String EID = TrEntryId.of(TID, "up-a");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static StringRedisTemplate redis;
    static TrKeySpace keys;
    static TrFeedRefreshService feed;
    static HttpServer mockUpstream;
    static AtomicReference<String> feedBody = new AtomicReference<>("{\"entries\":[]}");
    static String feedUrl;

    @BeforeAll
    static void setUp() throws IOException {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        keys = new TrKeySpace("tr");

        mockUpstream = HttpServer.create(new InetSocketAddress(0), 0);
        mockUpstream.createContext("/feed", exchange -> {
            byte[] body = feedBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        mockUpstream.start();
        feedUrl = "http://localhost:" + mockUpstream.getAddress().getPort() + "/feed";

        TrTableDefinition table = new TrTableDefinition();
        table.setName(TID);
        table.setRefreshUrl(feedUrl);
        TrTableRegistry registry = TrTableRegistry.load(List.of(table));
        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry()).loadAll(registry);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        feed = new TrFeedRefreshService(new TrRedis(manager, "main"), keys, registry,
                new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());
    }

    @AfterAll
    static void tearDown() {
        mockUpstream.stop(0);
    }

    private void reset() {
        redis.delete(List.of(keys.entryIds(TID), keys.feed(TID), keys.win(EID),
                keys.logState(EID), keys.affinity(TID, "s-1")));
        redis.opsForValue().set(keys.entry(TID, EID),
                "{\"entry_id\":\"" + EID + "\",\"name\":\"up-a\",\"weight\":100,\"order_no\":1,\"status\":\"ACTIVE\",\"data_json\":{}}");
        redis.opsForSet().add(keys.entryIds(TID), EID);
    }

    private static String entryJson(String name, String status, String dataJson) {
        return "{\"name\":\"" + name + "\",\"status\":" + (status == null ? "null" : "\"" + status + "\"")
                + ",\"data_json\":" + dataJson + "}";
    }

    @Test
    void refreshPullsAndUpsertsByDeterministicId() {
        reset();
        feedBody.set("{\"entries\":[" + entryJson("up-a", "ACTIVE", "{\"base_url\":\"https://a\"}")
                + "," + entryJson("up-b", null, "{}") + "]}");

        TrFeedRefreshService.Result r = feed.pullNow(TID);

        assertThat(r.success()).isTrue();
        assertThat(r.pulled()).isEqualTo(2);
        assertThat(r.upserted()).isEqualTo(2);
        assertThat(r.removed()).isZero();
        Set<String> ids = redis.opsForSet().members(keys.entryIds(TID));
        assertThat(ids).contains(EID, TrEntryId.of(TID, "up-b"));
        String b = redis.opsForValue().get(keys.entry(TID, TrEntryId.of(TID, "up-b")));
        assertThat(b).contains("\"weight\":100"); // 缺省 weight=100
        // 拉取游标落账
        assertThat(redis.opsForHash().get(keys.feed(TID), "fail_count")).isEqualTo("0");
    }

    @Test
    void fullComparisonRemovesMissingEntries() {
        reset();
        String goneId = TrEntryId.of(TID, "gone");
        redis.opsForSet().add(keys.entryIds(TID), goneId);
        redis.opsForValue().set(keys.entry(TID, goneId),
                "{\"entry_id\":\"" + goneId + "\",\"name\":\"gone\",\"status\":\"ACTIVE\",\"data_json\":{}}");
        feedBody.set("{\"entries\":[" + entryJson("up-a", null, "{}") + "]}");

        TrFeedRefreshService.Result r = feed.pullNow(TID);

        assertThat(r.success()).isTrue();
        assertThat(r.removed()).isEqualTo(1);
        assertThat(redis.opsForSet().members(keys.entryIds(TID))).containsExactly(EID);
        assertThat(redis.opsForValue().get(keys.entry(TID, goneId))).isNull();
    }

    @Test
    void statusDefaultsKeepExistingButExplicitActiveThaws() {
        reset();
        // 现值 FROZEN：feed 缺省 status → 保留 FROZEN + 冻结窗
        redis.opsForValue().set(keys.entry(TID, EID),
                "{\"entry_id\":\"" + EID + "\",\"name\":\"up-a\",\"weight\":100,\"order_no\":1,"
                        + "\"status\":\"FROZEN\",\"frozen_until\":9999999999999,\"freeze_count\":2,\"data_json\":{}}");
        feedBody.set("{\"entries\":[" + entryJson("up-a", null, "{}") + "]}");
        feed.pullNow(TID);
        String kept = redis.opsForValue().get(keys.entry(TID, EID));
        assertThat(kept).contains("FROZEN").contains("\"freeze_count\":2");

        // 显式 ACTIVE = 管理位解冻
        feedBody.set("{\"entries\":[" + entryJson("up-a", "ACTIVE", "{}") + "]}");
        feed.pullNow(TID);
        String thawed = redis.opsForValue().get(keys.entry(TID, EID));
        assertThat(thawed).contains("\"status\":\"ACTIVE\"");
        assertThat(thawed).doesNotContain("frozen_until");
    }

    @Test
    void schemaViolationKeepsOldValueAndCountsFailure() {
        reset();
        String before = redis.opsForValue().get(keys.entry(TID, EID));

        // 非法 status
        feedBody.set("{\"entries\":[" + entryJson("up-a", "PAUSED", "{}") + "]}");
        TrFeedRefreshService.Result bad = feed.pullNow(TID);
        assertThat(bad.success()).isFalse();
        assertThat(bad.error()).contains("10633");
        // data_json 超 8KB
        feedBody.set("{\"entries\":[" + entryJson("up-a", "ACTIVE", "{\"blob\":\""
                + "x".repeat(9 * 1024) + "\"}") + "]}");
        assertThat(feed.pullNow(TID).success()).isFalse();
        // 坏 entries（缺 name）
        feedBody.set("{\"entries\":[{\"weight\":1}]}");
        assertThat(feed.pullNow(TID).success()).isFalse();

        // 保旧值：条目未被破坏，连续失败计数 ≥3
        assertThat(redis.opsForValue().get(keys.entry(TID, EID))).isEqualTo(before);
        Object fails = redis.opsForHash().get(keys.feed(TID), "fail_count");
        assertThat(Integer.parseInt(String.valueOf(fails))).isGreaterThanOrEqualTo(3);
    }

    @Test
    void evaluatorDegradeLadderRecoveryAndThaw() {
        reset();
        long now = System.currentTimeMillis();

        // 窗内 12 调用 8 失败（66% ≥50%）→ ACTIVE→L1
        freshWin(now, 4, 8);
        List<Object> r1 = evaluate(now);
        assertThat(r1.get(0)).isEqualTo("MOVED");
        assertThat(r1.get(1)).isEqualTo("DEGRADED_L1");

        // L1 再达标 → L2；L2 再达标 → L3
        freshWin(now, 4, 8);
        assertThat(evaluate(now).get(1)).isEqualTo("DEGRADED_L2");
        freshWin(now, 4, 8);
        assertThat(evaluate(now).get(1)).isEqualTo("DEGRADED_L3");

        // 恢复：窗内调用 ≥10 且失败率 <10% → 一步恢复 ACTIVE
        freshWin(now, 12, 1);
        assertThat(evaluate(now).get(1)).isEqualTo("ACTIVE");

        // 惰性解冻：FROZEN 且窗满
        redis.opsForValue().set(keys.entry(TID, EID),
                "{\"entry_id\":\"" + EID + "\",\"name\":\"up-a\",\"weight\":100,\"order_no\":1,"
                        + "\"status\":\"FROZEN\",\"frozen_until\":" + (now - 1) + ",\"data_json\":{}}");
        List<Object> thaw = evaluate(now);
        assertThat(thaw.get(0)).isEqualTo("THAWED");
        assertThat(redis.opsForValue().get(keys.entry(TID, EID))).contains("\"status\":\"ACTIVE\"");
    }

    /** 清窗后推入 ok/fail 窗口（成员格式 {seq}:{ok|fail}，03 §2.3） */
    private void freshWin(long now, int ok, int fail) {
        redis.delete(keys.win(EID));
        for (int i = 0; i < ok; i++) {
            redis.opsForZSet().add(keys.win(EID), i + ":ok", now - 30000);
        }
        for (int i = 0; i < fail; i++) {
            redis.opsForZSet().add(keys.win(EID), (100 + i) + ":fail", now - 30000);
        }
    }

    /** 递进时间（下一评估窗），避免 60s 评估窗边界歧义 */
    private List<Object> evaluateLater(long base, int minute) {
        return evaluate(base + minute * 60_000L);
    }

    private List<Object> evaluate(long now) {
        return redis.execute(fun.commons.tokenroute.redis.TrLua.EVALUATE_TRANSITION,
                List.of(keys.entry(TID, EID), keys.win(EID), keys.logState(EID)),
                String.valueOf(now), "10", "0.5", "0.1");
    }
}
