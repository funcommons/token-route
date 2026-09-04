package fun.commons.tokenroute.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.admin.TrStateAdminService;
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
 * 状态重置 IT（TR-ADM-005，issue #1 追加）：余额不足停用场景闭环——
 * FROZEN（连败/充值不足冻结）→ resolve 排水 EMPTY → 运营 reset → ACTIVE +
 * 连败/1m 窗/退避升档清零 + 迁移史 ADMIN_RESET → resolve 恢复供给（resolve 流程零改动验证）。
 * OFFLINE（FEED 管理位）拒绝重置。需 Docker；-Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrStateAdminIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";
    private static final String TID = "llm";
    private static final String EID = TrEntryId.of(TID, "up-a");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static StringRedisTemplate redis;
    static TrKeySpace keys;
    static TrStateAdminService admin;
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

        admin = new TrStateAdminService(trRedis, keys, registry,
                new TrFeedBackfill.Noop(), Clock.systemUTC());
        resolve = new TrResolveService(trRedis, keys, registry, scripts, new TrScriptEngine(500),
                new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());

        seedFrozen();
    }

    @AfterAll
    static void tearDown() {
        // 容器由 JUnit 扩展停止
    }

    /** 种子：条目 FROZEN（退避窗内 + freeze_count=2）+ 连败计数 + 1m 窗残留 */
    private static void seedFrozen() {
        long now = System.currentTimeMillis();
        redis.opsForSet().add(keys.entryIds(TID), EID);
        redis.opsForValue().set(keys.entry(TID, EID),
                "{\"entry_id\":\"" + EID + "\",\"name\":\"up-a\",\"weight\":100,\"order_no\":1,"
                        + "\"status\":\"FROZEN\",\"frozen_until\":" + (now + 3_600_000)
                        + ",\"freeze_count\":2,\"data_json\":{}}");
        redis.opsForValue().set(keys.fail(EID), "5");
        redis.opsForZSet().add(keys.win(EID), now + ":fail", now);
    }

    @Test
    void frozenEntryDrainsThenResetsAndServesAgain() {
        // 冻结中：resolve 排水 → EMPTY + ALL_FILTERED（01 §4.2 完全退出）
        TrResolveResponse drained = resolve.resolve(req("task-r"), "mmagix-channel-domain");
        assertThat(drained.getEntryId()).isNull();
        assertThat(drained.getReasons()).contains(TrResolveResponse.R_ALL_FILTERED);

        // 运营重置：previous_status=FROZEN，复位 ACTIVE
        Map<String, Object> out = admin.reset(TID, EID, "10.0.0.1");
        assertThat(out.get("reset")).isEqualTo(true);
        assertThat(out.get("previous_status")).isEqualTo("FROZEN");

        // 全新开始：连败/1m 窗清零，退避升档 freeze_count 清空，迁移史 ADMIN_RESET
        assertThat(redis.opsForValue().get(keys.fail(EID))).isNull();
        assertThat(redis.opsForZSet().size(keys.win(EID))).isZero();
        String entryJson = redis.opsForValue().get(keys.entry(TID, EID));
        assertThat(entryJson).contains("\"status\":\"ACTIVE\"").doesNotContain("frozen_until").doesNotContain("freeze_count");
        List<String> logs = redis.opsForList().range(keys.logState(EID), 0, -1);
        assertThat(logs).isNotNull().isNotEmpty();
        assertThat(logs.get(logs.size() - 1)).contains("ADMIN_RESET").contains("FROZEN");

        // 恢复供给：resolve 立即可选中（resolve 流程零改动）
        TrResolveResponse served = resolve.resolve(req("task-r2"), "mmagix-channel-domain");
        assertThat(served.getEntryId()).isEqualTo(EID);
        assertThat(served.getLeaseId()).isNotBlank();

        // 幂等：已 ACTIVE 且无连败残留 → reset=false
        assertThat(admin.reset(TID, EID, "10.0.0.1").get("reset")).isEqualTo(false);
    }

    @Test
    void offlineIsFeedManagedAndRejected() {
        redis.opsForValue().set(keys.entry(TID, EID),
                "{\"entry_id\":\"" + EID + "\",\"name\":\"up-a\",\"weight\":100,\"order_no\":1,"
                        + "\"status\":\"OFFLINE\",\"data_json\":{}}");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.reset(TID, EID, "ip"))
                    .isInstanceOf(fun.commons.framework4j.web.ApiException.class)
                    .hasMessageContaining("OFFLINE");
        } finally {
            // 还原 FROZEN 种子供其他用例
            seedFrozen();
        }
    }

    private static TrResolveRequest req(String session) {
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId(TID);
        r.setSessionId(session);
        return r;
    }
}
