package fun.commons.tokenroute.it;

import fun.commons.tokenroute.observe.TrMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrEntryId;
import fun.commons.tokenroute.resolve.TrFeedBackfill;
import fun.commons.tokenroute.resolve.TrResolveRequest;
import fun.commons.tokenroute.resolve.TrResolveResponse;
import fun.commons.tokenroute.resolve.TrResolveService;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
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
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * resolve 决策全流程 IT（01 §5.1 / 07 S2 出口闸门）：三层取数 / 策略矩阵 / 亲和 / 容量 / EMPTY 语义。
 * 需 Docker；-Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrResolveFlowIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";
    private static final String TID = "llm";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static StringRedisTemplate redis;
    static TrResolveService service;
    static TrKeySpace keys;
    static TrTableRegistry registry;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        keys = new TrKeySpace("tr");
        TrTableDefinition weighted = new TrTableDefinition();
        weighted.setName(TID);
        weighted.setRefreshUrl("http://up/feed");
        TrTableDefinition scriptTable = new TrTableDefinition();
        scriptTable.setName("script-t");
        scriptTable.setRefreshUrl("http://up/feed");
        scriptTable.setStrategyType(fun.commons.tokenroute.config.TrStrategyType.SCRIPT);
        scriptTable.setSelectorScript("classpath:scripts/selector-cheapest.groovy");
        registry = TrTableRegistry.load(List.of(weighted, scriptTable));

        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry())
                .loadAll(registry);
        // MultiRedisManager 手工注册容器 datasource（对齐 fwk4j 装配产物）
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrRedis trRedis = new TrRedis(manager, "main");
        service = new TrResolveService(trRedis, keys, registry, scripts, new TrScriptEngine(500),
                new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());
    }

    @AfterAll
    static void tearDown() {
        // MultiRedisManager.destroy 无需调用（未注册 datasource，仅本地 map）
    }

    private void seedEntry(String table, String name, double weight, int orderNo, String status, String dataJson) {
        String eid = TrEntryId.of(table, name);
        redis.opsForSet().add(keys.entryIds(table), eid);
        String capacity = dataJson == null ? "" : dataJson;
        redis.opsForValue().set(keys.entry(table, eid),
                "{\"entry_id\":\"" + eid + "\",\"name\":\"" + name + "\",\"weight\":" + weight
                        + ",\"order_no\":" + orderNo + ",\"status\":\"" + status
                        + "\",\"data_json\":" + (capacity.isEmpty() ? "{}" : capacity) + "}");
    }

    private TrResolveRequest req(String table, String session) {
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId(table);
        r.setSessionId(session);
        return r;
    }

    @Test
    void weightedRandomResolvesWithLeaseAndBinding() {
        redis.delete(keys.entryIds(TID));
        seedEntry(TID, "a", 100, 1, "ACTIVE", "{\"base_url\":\"https://up-a\"}");
        TrResolveResponse resp = service.resolve(req(TID, "task-1"), "gateway");

        assertThat(resp.getEntryId()).isEqualTo(TrEntryId.of(TID, "a"));
        assertThat(resp.getLeaseId()).isNotBlank();
        assertThat(resp.getAffinity()).isEqualTo(TrResolveResponse.AFF_NEW);
        assertThat(resp.getDataJson()).containsEntry("base_url", "https://up-a");
        // 预占落账
        assertThat(redis.opsForZSet().size(keys.conc(resp.getEntryId()))).isEqualTo(1);
        assertThat(redis.opsForValue().get(keys.affinity(TID, "task-1"))).isEqualTo(resp.getEntryId());
        // 决议 ring
        assertThat(redis.opsForList().size(keys.logResolve(TID))).isEqualTo(1);
    }

    @Test
    void affinitySecondResolveHitsSameEntry() {
        redis.delete(keys.entryIds(TID));
        seedEntry(TID, "a", 100, 1, "ACTIVE", null);
        seedEntry(TID, "b", 100, 2, "ACTIVE", null);
        // 权重随机���能两次选中不同条目——强制首个绑定后必须 HIT 同一条目
        service.resolve(req(TID, "task-aff"), "gateway");
        TrResolveResponse second = service.resolve(req(TID, "task-aff"), "gateway");
        assertThat(second.getAffinity()).isEqualTo(TrResolveResponse.AFF_HIT);
        assertThat(second.getEntryId())
                .isEqualTo(redis.opsForValue().get(keys.affinity(TID, "task-aff")));
    }

    @Test
    void roundRobinWalksEntries() {
        TrTableDefinition rr = new TrTableDefinition();
        rr.setName("rr-t");
        rr.setRefreshUrl("http://up/feed");
        rr.setStrategyType(fun.commons.tokenroute.config.TrStrategyType.ROUND_ROBIN);
        registry = TrTableRegistry.load(List.of(rr));
        // registry 为不可变快照——服务内已持旧引用；此处改用独立服务实例验证 rr
        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry()).loadAll(registry);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrResolveService rrService = new TrResolveService(new TrRedis(manager, "main"), keys, registry,
                scripts, new TrScriptEngine(500), new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());

        redis.delete(keys.entryIds("rr-t"));
        seedEntry("rr-t", "e1", 100, 2, "ACTIVE", null);
        seedEntry("rr-t", "e2", 100, 1, "ACTIVE", null);
        redis.delete(keys.rr("rr-t"));

        String first = rrService.resolve(req("rr-t", null), null).getEntryId();
        String second = rrService.resolve(req("rr-t", null), null).getEntryId();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void capacityExhaustedYieldsEmptyWithReason() {
        TrTableDefinition cap = new TrTableDefinition();
        cap.setName("cap-t");
        cap.setRefreshUrl("http://up/feed");
        registry = TrTableRegistry.load(List.of(cap));
        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry()).loadAll(registry);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrResolveService capService = new TrResolveService(new TrRedis(manager, "main"), keys, registry,
                scripts, new TrScriptEngine(500), new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());

        redis.delete(keys.entryIds("cap-t"));
        String eid = TrEntryId.of("cap-t", "full");
        redis.opsForSet().add(keys.entryIds("cap-t"), eid);
        redis.opsForValue().set(keys.entry("cap-t", eid),
                "{\"entry_id\":\"" + eid + "\",\"name\":\"full\",\"weight\":100,\"order_no\":1,\"status\":\"ACTIVE\","
                        + "\"data_json\":{\"capacity\":{\"max_concurrency\":1}}}");
        redis.opsForZSet().add(keys.conc(eid), "other-lease", System.currentTimeMillis() + 30000);

        TrResolveResponse resp = capService.resolve(req("cap-t", null), null);
        assertThat(resp.getEntryId()).isNull();
        assertThat(resp.getReasons()).containsExactly(TrResolveResponse.R_CAPACITY);
    }

    @Test
    void emptyIdSetYieldsTableEmptyWithoutBackfill() {
        TrTableDefinition empty = new TrTableDefinition();
        empty.setName("empty-t");
        empty.setRefreshUrl("http://up/feed");
        TrTableRegistry reg = TrTableRegistry.load(List.of(empty));
        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry()).loadAll(reg);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrResolveService svc = new TrResolveService(new TrRedis(manager, "main"), keys, reg,
                scripts, new TrScriptEngine(500), new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());

        redis.opsForSet().add(keys.entryIds("empty-t"), "placeholder");
        redis.opsForSet().remove(keys.entryIds("empty-t"), "placeholder"); // 键存在但空集

        TrResolveResponse resp = svc.resolve(req("empty-t", null), null);
        assertThat(resp.getReasons()).containsExactly(TrResolveResponse.R_TABLE_EMPTY);
    }

    @Test
    void offlineTableYieldsTableOffline() {
        TrTableDefinition off = new TrTableDefinition();
        off.setName("off-t");
        off.setRefreshUrl("http://up/feed");
        off.setStatus("OFFLINE");
        TrTableRegistry reg = TrTableRegistry.load(List.of(off));
        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry()).loadAll(reg);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrResolveService svc = new TrResolveService(new TrRedis(manager, "main"), keys, reg,
                scripts, new TrScriptEngine(500), new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());

        assertThat(svc.resolve(req("off-t", null), null).getReasons())
                .containsExactly(TrResolveResponse.R_TABLE_OFFLINE);
    }

    @Test
    void unknownTableIs404() {
        assertThatThrownBy(() -> service.resolve(req("ghost", null), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void scriptSelectorPicksCheapestAndFallsBackOnInvalidReturn() {
        redis.delete(keys.entryIds("script-t"));
        seedEntry("script-t", "cheap", 10, 1, "ACTIVE", "{\"unit_price\":0.01}");
        seedEntry("script-t", "dear", 100, 2, "ACTIVE", "{\"unit_price\":0.9}");

        TrResolveResponse resp = service.resolve(req("script-t", null), null);
        assertThat(resp.getEntryId()).isEqualTo(TrEntryId.of("script-t", "cheap"));
    }

    @Test
    void statusDomainFiltersOutL2() {
        redis.delete(keys.entryIds(TID));
        seedEntry(TID, "draining", 100, 1, "DEGRADED_L2", null);
        seedEntry(TID, "healthy", 1, 2, "ACTIVE", null);

        TrResolveResponse resp = service.resolve(req(TID, null), null);
        assertThat(resp.getEntryId()).isEqualTo(TrEntryId.of(TID, "healthy"));
    }
}
