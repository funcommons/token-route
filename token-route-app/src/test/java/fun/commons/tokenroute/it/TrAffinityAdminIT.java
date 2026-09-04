package fun.commons.tokenroute.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.admin.TrAffinityAdminService;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.observe.TrMetrics;
import fun.commons.tokenroute.redis.TrRedis;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 亲和管理 IT（issue #1）：set/get/list/delete 对真实亲和键的数据正确性 +
 * 改流闭环——admin set 后 resolve 亲和 HIT 新指向、delete 后 resolve 重绑（resolve 流程零改动验证）。
 * 需 Docker；-Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrAffinityAdminIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";
    private static final String TID = "llm";
    private static final String EID_A = TrEntryId.of(TID, "up-a");
    private static final String EID_B = TrEntryId.of(TID, "up-b");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static StringRedisTemplate redis;
    static TrKeySpace keys;
    static TrAffinityAdminService admin;
    static TrResolveService resolve;

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

        admin = new TrAffinityAdminService(trRedis, keys, registry,
                new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC());
        resolve = new TrResolveService(trRedis, keys, registry, scripts, new TrScriptEngine(500),
                new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());

        seedEntry("up-a", EID_A);
        seedEntry("up-b", EID_B);
    }

    @AfterAll
    static void tearDown() {
        // 容器由 JUnit 扩展停止
    }

    @Test
    void setGetRoundtripWithTableDefaultTtl() {
        Map<String, Object> out = admin.set(TID, "task-1", EID_A, null, "10.0.0.1");
        assertThat(out.get("ttl_seconds")).isEqualTo(28800);
        assertThat((String) out.get("previous_entry_id")).isNull();

        Map<String, Object> got = admin.get(TID, "task-1");
        assertThat(got.get("found")).isEqualTo(true);
        assertThat(got.get("entry_id")).isEqualTo(EID_A);
        assertThat((Long) got.get("ttl_remaining_seconds")).isBetween(28000L, 28800L);
    }

    @Test
    void setOverwritesWithCustomTtlAndEchoesPrevious() {
        admin.set(TID, "task-2", EID_A, 300, "10.0.0.1");
        Map<String, Object> out = admin.set(TID, "task-2", EID_B, 60, "10.0.0.1");
        assertThat(out.get("previous_entry_id")).isEqualTo(EID_A);
        assertThat(out.get("ttl_seconds")).isEqualTo(60);

        Map<String, Object> got = admin.get(TID, "task-2");
        assertThat(got.get("entry_id")).isEqualTo(EID_B);
        assertThat((Long) got.get("ttl_remaining_seconds")).isBetween(55L, 60L);
    }

    @Test
    void resolveHitsAdminSetBindingThenRebindsAfterDelete() {
        // 改流闭环：admin set → 下一 resolve 亲和 HIT 新指向（resolve 流程零改动）
        admin.set(TID, "task-3", EID_B, null, "10.0.0.1");
        TrResolveResponse hit = resolve.resolve(req("task-3"), "mmagix-channel-domain");
        assertThat(hit.getEntryId()).isEqualTo(EID_B);
        assertThat(hit.getAffinity()).isEqualTo(TrResolveResponse.AFF_HIT);

        // delete → 亲和解除；幂等：再删（未再 resolve）→ deleted=false
        Map<String, Object> deleted = admin.delete(TID, "task-3", "10.0.0.1");
        assertThat(deleted.get("deleted")).isEqualTo(true);
        assertThat(deleted.get("entry_id")).isEqualTo(EID_B);
        assertThat(admin.get(TID, "task-3").get("found")).isEqualTo(false);
        assertThat(admin.delete(TID, "task-3", "10.0.0.1").get("deleted")).isEqualTo(false);

        // 解除后 resolve 重绑（NEW）
        TrResolveResponse rebound = resolve.resolve(req("task-3"), "mmagix-channel-domain");
        assertThat(rebound.getAffinity()).isEqualTo(TrResolveResponse.AFF_NEW);
        assertThat(rebound.getEntryId()).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFiltersByPrefixAndRestoresSessionIds() {
        admin.set(TID, "key-1:gpt-4o", EID_A, null, "10.0.0.1");
        admin.set(TID, "key-1:claude-3", EID_A, null, "10.0.0.1");
        admin.set(TID, "key-2:gpt-4o", EID_B, null, "10.0.0.1");

        Map<String, Object> out = admin.list(TID, "key-1:", null);
        assertThat(out.get("complete")).isEqualTo(true);
        assertThat(out.get("count")).isEqualTo(2);
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        // MMagiX 会话形态 {apiKeyId}:{model}——前缀定位 + session_id 还原
        assertThat(items).extracting(m -> m.get("session_id"))
                .containsExactly("key-1:claude-3", "key-1:gpt-4o");

        Map<String, Object> all = admin.list(TID, null, 500);
        assertThat((List<Map<String, Object>>) all.get("items"))
                .extracting(m -> m.get("session_id")).contains("key-2:gpt-4o");
    }

    @Test
    void affinityEventsRecordAdminOperations() {
        admin.set(TID, "task-evt", EID_A, null, "10.0.0.1");
        admin.delete(TID, "task-evt", "10.0.0.1");
        List<String> events = redis.opsForList().range(keys.logAff(TID), 0, -1);
        assertThat(events).isNotNull();
        // ring 为全表共享（其他用例并发落事件）：按 session 精确归因
        assertThat(events.stream().filter(e -> e.contains("\"session\":\"task-evt\"")
                && e.contains("ADMIN_SET"))).hasSize(1);
        assertThat(events.stream().filter(e -> e.contains("\"session\":\"task-evt\"")
                && e.contains("ADMIN_DELETE"))).hasSize(1);
    }

    private static void seedEntry(String name, String eid) {
        redis.opsForSet().add(keys.entryIds(TID), eid);
        redis.opsForValue().set(keys.entry(TID, eid),
                "{\"entry_id\":\"" + eid + "\",\"name\":\"" + name + "\",\"weight\":100,\"order_no\":1,"
                        + "\"status\":\"ACTIVE\",\"data_json\":{}}");
    }

    private static TrResolveRequest req(String session) {
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId(TID);
        r.setSessionId(session);
        return r;
    }
}
