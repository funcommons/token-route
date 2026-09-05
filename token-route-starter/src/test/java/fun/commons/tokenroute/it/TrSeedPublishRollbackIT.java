package fun.commons.tokenroute.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.observe.TrMetrics;
import fun.commons.tokenroute.ops.TrOpsService;
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
 * 种子「发布 → 生效 → 回滚」用例（issue #3 R2 验收，仓内侧证明）：
 * TokenGo 策略版本发布后离线生成本仓种子（零管理写面不变），随滚动重启生效。
 * 版本差异由种子自带的过滤脚本表达（v1 放行渠道 A / v2 切换渠道 B）——
 * 同一 Redis + FEED 数据不动，仅换种子构造的 registry 即模拟「重启前后的两个进程视图」：
 * 发布 v2 → resolve 按新版本生效 + ops 回显 metadata 策略版本号；回滚 → 重载 v1 恢复渠道 A。
 * 部署级滚动重启无感见 docs/用户文档/上线检查单.md（双实例 + graceful shutdown；
 * TokenGo 独立实例部署成本极低，见 issue #3 影响面护栏——推荐独立实例）。
 * 需 Docker（Redis）；-Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrSeedPublishRollbackIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";
    private static final String TID = "tokengo-strategy";
    private static final String ENTRY_A = TrEntryId.of(TID, "tg-channel-a");
    private static final String ENTRY_B = TrEntryId.of(TID, "tg-channel-b");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static StringRedisTemplate redis;
    static TrKeySpace keys;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        keys = new TrKeySpace("tr");
        // FEED 面两渠道并存（导出器全量）；选哪条由种子（策略版本）决定
        for (String[] e : new String[][]{{ENTRY_A, "tg-channel-a"}, {ENTRY_B, "tg-channel-b"}}) {
            redis.opsForSet().add(keys.entryIds(TID), e[0]);
            redis.opsForValue().set(keys.entry(TID, e[0]),
                    "{\"entry_id\":\"" + e[0] + "\",\"name\":\"" + e[1] + "\",\"weight\":100,\"order_no\":1,"
                            + "\"status\":\"ACTIVE\",\"data_json\":{\"channel\":\"" + e[1] + "\"}}");
        }
    }

    @AfterAll
    static void tearDown() {
        // 容器由 JUnit 扩展停止
    }

    /** 一版种子 = 一次策略发布产物：filter 脚本 + metadata 版本号 */
    private static TrTableDefinition seed(String version, String filterScript) {
        TrTableDefinition t = new TrTableDefinition();
        t.setName(TID);
        t.setRefreshUrl("http://tokengo/export/" + version);
        t.setFilterScript("classpath:scripts/" + filterScript);
        t.setMetadata(Map.of("strategy-version", "tokengo-" + version,
                "published-at", "2026-09-05T03:00:00Z"));
        return t;
    }

    private static TrResolveService resolveOn(TrTableDefinition seedTable) {
        TrTableRegistry registry = TrTableRegistry.load(List.of(seedTable));
        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry())
                .loadAll(registry);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrRedis trRedis = new TrRedis(manager, "main");
        return new TrResolveService(trRedis, keys, registry, scripts, new TrScriptEngine(500),
                new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());
    }

    @Test
    void publishEffectThenRollbackRestores() {
        // —— 基线：v1 种子（渠道 A）——
        TrResolveResponse before = resolveOn(seed("v1", "seed-v1-filter.groovy")).resolve(req(), "tokengo");
        assertThat(before.getEntryId()).isEqualTo(ENTRY_A);

        // —— 发布 v2：滚动重启后进程持有新种子 → resolve 按新版本生效（切换渠道 B）——
        TrTableDefinition v2Seed = seed("v2", "seed-v2-filter.groovy");
        TrResolveResponse after = resolveOn(v2Seed).resolve(req(), "tokengo");
        assertThat(after.getEntryId()).isEqualTo(ENTRY_B);

        // 版本追溯：ops 只读面（TR-OPS-001）回显 metadata——TokenGo 策略版本号随种子可见
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrOpsService ops = new TrOpsService(new TrRedis(manager, "main"), keys,
                TrTableRegistry.load(List.of(v2Seed)), new ObjectMapper());
        Map<String, Object> status = ops.tableStatus(TID);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) status.get("metadata");
        assertThat(metadata).containsEntry("strategy-version", "tokengo-v2");

        // —— 回滚：重载 v1 种子 → 恢复渠道 A（Redis/FEED 不动，回滚 = 换回旧种子重启）——
        TrResolveResponse rolledBack = resolveOn(seed("v1", "seed-v1-filter.groovy")).resolve(req(), "tokengo");
        assertThat(rolledBack.getEntryId()).isEqualTo(ENTRY_A);
    }

    private static TrResolveRequest req() {
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId(TID);
        return r;
    }
}
