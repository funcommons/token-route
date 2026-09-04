package fun.commons.tokenroute.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.ops.TrOpsService;
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

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 IT：ops 只读查询面（TR-OPS-001~004）数据正确性。
 * 鉴权矩阵（none/apikey/jwt、两面互不可越）已由 TrAuth*Test 覆盖。需 Docker；-Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrOpsFlowIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";
    private static final String TID = "llm";
    private static final String EID = TrEntryId.of(TID, "up-a");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static TrOpsService ops;
    static StringRedisTemplate redis;
    static TrKeySpace keys;

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
        new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry()).loadAll(registry);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        ops = new TrOpsService(new TrRedis(manager, "main"), keys, registry, new ObjectMapper());

        // 种子：条目 + 并发租约 + 决议/亲和/状态日志
        redis.opsForSet().add(keys.entryIds(TID), EID);
        redis.opsForValue().set(keys.entry(TID, EID),
                "{\"entry_id\":\"" + EID + "\",\"name\":\"up-a\",\"weight\":100,\"order_no\":1,\"status\":\"ACTIVE\",\"data_json\":{}}");
        redis.opsForZSet().add(keys.conc(EID), "lease-1", System.currentTimeMillis() + 30_000);
        redis.opsForList().rightPush(keys.logResolve(TID),
                "{\"at\":" + System.currentTimeMillis() + ",\"table\":\"llm\",\"entry_id\":\"" + EID + "\",\"empty\":false}");
        redis.opsForList().rightPush(keys.logAff(TID),
                "{\"type\":\"BIND\",\"session\":\"task-1\",\"entry_id\":\"" + EID + "\"}");
        redis.opsForList().rightPush(keys.logState(EID),
                "{\"from\":\"ACTIVE\",\"to\":\"FROZEN\",\"reason\":\"DISABLE\",\"by\":\"REPORT_DISABLE\"}");
        redis.opsForValue().set(keys.affinity(TID, "task-1"), EID);
    }

    @AfterAll
    static void tearDown() {
        // 容器由 JUnit 扩展停止
    }

    @Test
    void tableStatusShowsRuntimeState() {
        Map<String, Object> status = ops.tableStatus(TID);
        assertThat(status.get("table_id")).isEqualTo(TID);
        assertThat(status.get("affinity_active_count")).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) status.get("entries");
        assertThat(entries).hasSize(1);
        Map<String, Object> e = entries.get(0);
        assertThat(e.get("entry_id")).isEqualTo(EID);
        assertThat(e.get("status")).isEqualTo("ACTIVE");
        assertThat(e.get("conc_active")).isEqualTo(1L);
        assertThat(e.get("win_calls")).isEqualTo(0);
    }

    @Test
    void resolveLogsReturnRingWindow() {
        List<Object> logs = ops.resolveLogs(TID, null, null, null, 1, 100);
        assertThat(logs).hasSize(1);
        assertThat(String.valueOf(logs.get(0))).contains(EID);
        // result 过滤不匹配 → 空
        assertThat(ops.resolveLogs(TID, null, null, "empty", 1, 100)).isEmpty();
    }

    @Test
    void affinityEventsFilterByType() {
        assertThat(ops.affinityEvents(TID, "BIND", 1, 100)).hasSize(1);
        assertThat(ops.affinityEvents(TID, "DETACH", 1, 100)).isEmpty();
    }

    @Test
    void stateLogsReturnMigrationHistory() {
        List<Object> logs = ops.stateLogs(EID, 1, 100);
        assertThat(logs).hasSize(1);
        assertThat(String.valueOf(logs.get(0))).contains("REPORT_DISABLE").contains("FROZEN");
    }
}
