package fun.commons.tokenroute.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.TrRouteEngine;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.observe.TrMetrics;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.report.TrReportService;
import fun.commons.tokenroute.resolve.TrEntryId;
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

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * detach 内核 IT（TR-CTR-003，嵌入式门面）：resolve 建绑 → detach 解除（DEL + DETACH 事件）
 * → 再 resolve 重绑 NEW；无绑定时幂等 false。需 Docker；-Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrDetachIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";
    private static final String TID = "llm";
    private static final String EID = TrEntryId.of(TID, "up-a");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static StringRedisTemplate redis;
    static TrKeySpace keys;
    static TrRouteEngine engine;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        keys = new TrKeySpace("tr");

        TrTableDefinition table = new TrTableDefinition();
        table.setName(TID);
        table.setRefreshUrl("http://up/feed");
        TrTableRegistry registry = TrTableRegistry.load(List.of(table));
        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry())
                .loadAll(registry);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrRedis trRedis = new TrRedis(manager, "main");
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        engine = new TrRouteEngine(
                new TrResolveService(trRedis, keys, registry, scripts, new TrScriptEngine(500),
                        new TrFeedBackfill.Noop(), mapper, clock, TrMetrics.noop()),
                new TrReportService(trRedis, keys, registry, clock, TrMetrics.noop()),
                trRedis, keys, mapper, clock);

        redis.opsForSet().add(keys.entryIds(TID), EID);
        redis.opsForValue().set(keys.entry(TID, EID),
                "{\"entry_id\":\"" + EID + "\",\"name\":\"up-a\",\"weight\":100,\"order_no\":1,"
                        + "\"status\":\"ACTIVE\",\"data_json\":{}}");
    }

    @AfterAll
    static void tearDown() {
        // 容器由 JUnit 扩展停止
    }

    @Test
    void detachReleasesBindingAndNextResolveRebinds() {
        // ① resolve 建绑
        TrResolveResponse first = engine.resolve(req("task-d"));
        assertThat(first.getAffinity()).isEqualTo(TrResolveResponse.AFF_NEW);
        assertThat(first.getEntryId()).isEqualTo(EID);

        // ② 亲和命中确认绑定在
        assertThat(engine.resolve(req("task-d")).getAffinity()).isEqualTo(TrResolveResponse.AFF_HIT);

        // ③ detach：DEL + DETACH 事件（by=CONSUMER）
        assertThat(engine.detach(TID, "task-d")).isTrue();
        assertThat(redis.opsForValue().get(keys.affinity(TID, "task-d"))).isNull();
        List<String> events = redis.opsForList().range(keys.logAff(TID), 0, -1);
        assertThat(events).isNotNull().isNotEmpty();
        assertThat(events.stream().filter(e -> e.contains("CONSUMER") && e.contains("task-d"))).hasSize(1);

        // ④ 再 resolve 重绑 NEW（failover = detach → resolve）
        TrResolveResponse rebound = engine.resolve(req("task-d"));
        assertThat(rebound.getAffinity()).isEqualTo(TrResolveResponse.AFF_NEW);

        // ⑤ 幂等：解除后无绑定 → false
        engine.detach(TID, "task-d");
        assertThat(engine.detach(TID, "task-d")).isFalse();
    }

    private static TrResolveRequest req(String session) {
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId(TID);
        r.setSessionId(session);
        return r;
    }
}
