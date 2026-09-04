package fun.commons.tokenroute.it;

import fun.commons.tokenroute.observe.TrMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.report.TrReportRequest;
import fun.commons.tokenroute.report.TrReportResponse;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * report 三态与容量记账 IT（07 S3 出口闸门）：三态矩阵 / 双桶口径 / rate_units=0 / 连败冻结 /
 * DISABLE 即时脱离 / 迟到 lease 拒收 / 竞态单释放。需 Docker；-Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrReportFlowIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";
    private static final String TID = "llm";
    private static final String EID = TrEntryId.of(TID, "up-a");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static StringRedisTemplate redis;
    static TrKeySpace keys;
    static TrResolveService resolveService;
    static TrReportService reportService;

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
        TrScriptRegistry scripts = new TrScriptLoader(new TrScriptEngine(500), new TrScriptRegistry()).loadAll(registry);
        MultiRedisManager manager = new MultiRedisManager();
        manager.registerRedisTemplate("main", redis);
        TrRedis trRedis = new TrRedis(manager, "main");
        resolveService = new TrResolveService(trRedis, keys, registry, scripts, new TrScriptEngine(500),
                new TrFeedBackfill.Noop(), new ObjectMapper(), Clock.systemUTC(), TrMetrics.noop());
        reportService = new TrReportService(trRedis, keys, registry, Clock.systemUTC(), TrMetrics.noop());

        // 种子：单条目，TOKEN 限速 5000/1000ms
        redis.opsForSet().add(keys.entryIds(TID), EID);
        redis.opsForValue().set(keys.entry(TID, EID),
                "{\"entry_id\":\"" + EID + "\",\"name\":\"up-a\",\"weight\":100,\"order_no\":1,\"status\":\"ACTIVE\","
                        + "\"data_json\":{\"capacity\":{\"max_concurrency\":5,\"rate_limit_value\":5000,"
                        + "\"rate_window_ms\":1000,\"rate_unit\":\"TOKEN\"}}}");
    }

    private TrResolveRequest resolveReq(String session) {
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId(TID);
        r.setSessionId(session);
        return r;
    }

    private TrReportRequest.Item reportItem(String result) {
        TrReportRequest.Item item = new TrReportRequest.Item();
        item.setEntryId(EID);
        item.setLeaseId(UUID.randomUUID().toString());
        item.setResult(result);
        return item;
    }

    /** 绕过 resolve 手工预占一个存活租约（可控测试） */
    private void manualLease(String leaseId) {
        redis.opsForZSet().add(keys.conc(EID), leaseId, System.currentTimeMillis() + 30_000);
    }

    /** 每个测试自清状态：条目回 ACTIVE、计数/窗口清零（测试间 Redis 共享） */
    private void resetEntry() {
        redis.delete(List.of(keys.conc(EID), keys.fail(EID), keys.win(EID), keys.rateReq(EID),
                keys.rateUnit(EID), keys.seq(EID), keys.logState(EID)));
        seedActive();
    }

    @Test
    void successReleasesLeaseAndChargesBothBuckets() {
        resetEntry();
        TrResolveResponse resp = resolveService.resolve(resolveReq(null), null);
        String leaseId = resp.getLeaseId();

        TrReportRequest req = new TrReportRequest();
        req.setReports(List.of(reportItem("SUCCESS")));
        req.getReports().get(0).setLeaseId(leaseId);
        TrReportResponse out = reportService.report(req);

        assertThat(out.getAccepted()).isEqualTo(1);
        assertThat(out.getRejected()).isEmpty();
        // 精确释放
        assertThat(redis.opsForZSet().size(keys.conc(EID))).isZero();
        // 双桶记账（TOKEN 口径：req 1 次 + unit 1）
        assertThat(redis.opsForZSet().size(keys.rateReq(EID))).isEqualTo(1);
        assertThat(redis.opsForZSet().size(keys.rateUnit(EID))).isEqualTo(1);
        // 健康窗 ok 成员
        assertThat(redis.opsForZSet().size(keys.win(EID))).isEqualTo(1);
    }

    @Test
    void rateUnitsZeroLeavesZeroUnitEntry() {
        resetEntry();
        TrResolveResponse resp = resolveService.resolve(resolveReq(null), null);
        TrReportRequest.Item item = reportItem("SUCCESS");
        item.setLeaseId(resp.getLeaseId());
        item.setRateUnits(0.0); // 调用前拒绝：0 计量留痕
        TrReportRequest req = new TrReportRequest();
        req.setReports(List.of(item));

        assertThat(reportService.report(req).getAccepted()).isEqualTo(1);
        assertThat(redis.opsForZSet().size(keys.rateReq(EID))).isEqualTo(1);
        assertThat(redis.opsForZSet().range(keys.rateUnit(EID), 0, -1))
                .anySatisfy(m -> assertThat(m).endsWith(":0"));
    }

    @Test
    void consecutiveFailsFreezeAtThresholdWithBackoff() {
        resetEntry();
        long now = System.currentTimeMillis();
        // 4 次连败未到阈值（default 5）
        for (int i = 0; i < 4; i++) {
            String lease = "l-" + i;
            manualLease(lease);
            TrReportRequest req = new TrReportRequest();
            TrReportRequest.Item item = reportItem("RETRYABLE_FAIL");
            item.setLeaseId(lease);
            req.setReports(List.of(item));
            assertThat(reportService.report(req).getAccepted()).isEqualTo(1);
        }
        assertThat(redis.opsForValue().get(keys.fail(EID))).isEqualTo("4");
        assertThat(redis.opsForValue().get(keys.entry(TID, EID))).contains("\"status\":\"ACTIVE\"");

        // 第 5 次：即判 FROZEN，退避 = 5min × 4^0（首个冻结周期）
        String lease = "l-final";
        manualLease(lease);
        TrReportRequest req = new TrReportRequest();
        TrReportRequest.Item item = reportItem("RETRYABLE_FAIL");
        item.setLeaseId(lease);
        req.setReports(List.of(item));
        assertThat(reportService.report(req).getAccepted()).isEqualTo(1);

        String json = redis.opsForValue().get(keys.entry(TID, EID));
        assertThat(json).contains("\"status\":\"FROZEN\"");
        long frozenUntil = Long.parseLong(json.replaceAll(".*\"frozen_until\":([0-9]+).*", "$1"));
        assertThat(frozenUntil - now).isBetween(250_000L, 350_000L);
        // 状态迁移史
        assertThat(redis.opsForList().size(keys.logState(EID))).isEqualTo(1);
        // 冻结后 resolve 不再选中（态域出域）→ EMPTY ALL_FILTERED
        redis.delete(keys.entryIds(TID));
        redis.opsForSet().add(keys.entryIds(TID), EID);
        TrResolveResponse resp = resolveService.resolve(resolveReq(null), null);
        assertThat(resp.getEntryId()).isNull();
        assertThat(resp.getReasons()).containsExactly(TrResolveResponse.R_ALL_FILTERED);
    }

    @Test
    void disableFailFreezesImmediatelyAndDetachesSession() {
        resetEntry();
        redis.delete(keys.entryIds(TID));
        seedActive();
        String session = "task-x";
        TrResolveResponse resp = resolveService.resolve(resolveReq(session), null);
        assertThat(redis.opsForValue().get(keys.affinity(TID, session))).isNotNull();

        TrReportRequest req = new TrReportRequest();
        TrReportRequest.Item item = reportItem("DISABLE_FAIL");
        item.setLeaseId(resp.getLeaseId());
        item.setSessionId(session);
        req.setReports(List.of(item));

        assertThat(reportService.report(req).getAccepted()).isEqualTo(1);
        // 直写 FROZEN + 本会话即时脱离
        assertThat(redis.opsForValue().get(keys.entry(TID, EID))).contains("\"status\":\"FROZEN\"");
        assertThat(redis.opsForValue().get(keys.affinity(TID, session))).isNull();
    }

    @Test
    void successClearsConsecutiveFailCounter() {
        resetEntry();
        seedActive();
        for (int i = 0; i < 3; i++) {
            String lease = "f-" + i;
            manualLease(lease);
            TrReportRequest req = new TrReportRequest();
            TrReportRequest.Item item = reportItem("RETRYABLE_FAIL");
            item.setLeaseId(lease);
            req.setReports(List.of(item));
            reportService.report(req);
        }
        assertThat(redis.opsForValue().get(keys.fail(EID))).isEqualTo("3");

        String lease = "ok-1";
        manualLease(lease);
        TrReportRequest ok = new TrReportRequest();
        TrReportRequest.Item item = reportItem("SUCCESS");
        item.setLeaseId(lease);
        ok.setReports(List.of(item));
        reportService.report(ok);

        assertThat(redis.opsForValue().get(keys.fail(EID))).isNull();
    }

    @Test
    void lateOrUnknownLeaseIsRejected() {
        resetEntry();
        redis.delete(keys.entryIds(TID));
        seedActive();
        TrReportRequest req = new TrReportRequest();
        TrReportRequest.Item item = reportItem("SUCCESS");
        item.setLeaseId("never-leased");
        req.setReports(List.of(item));

        TrReportResponse out = reportService.report(req);
        assertThat(out.getAccepted()).isZero();
        assertThat(out.getRejected()).hasSize(1);
        assertThat(out.getRejected().get(0).getIndex()).isZero();
        assertThat(out.getRejected().get(0).getCode()).isEqualTo(10100);
    }

    @Test
    void unknownEntryIsRejectedWith404Code() {
        TrReportRequest req = new TrReportRequest();
        TrReportRequest.Item item = reportItem("SUCCESS");
        item.setEntryId("e-unknown");
        req.setReports(List.of(item));

        TrReportResponse out = reportService.report(req);
        assertThat(out.getRejected()).hasSize(1);
        assertThat(out.getRejected().get(0).getCode()).isEqualTo(10400);
    }

    @Test
    void mixedBatchProducesPartialAccept() {
        resetEntry();
        redis.delete(keys.entryIds(TID));
        seedActive();
        String lease = UUID.randomUUID().toString();
        manualLease(lease);

        TrReportRequest req = new TrReportRequest();
        TrReportRequest.Item ok = reportItem("SUCCESS");
        ok.setLeaseId(lease);
        TrReportRequest.Item bad = reportItem("SUCCESS");
        bad.setEntryId("e-ghost");
        req.setReports(List.of(ok, bad));

        TrReportResponse out = reportService.report(req);
        assertThat(out.getAccepted()).isEqualTo(1);
        assertThat(out.getRejected()).hasSize(1);
        assertThat(out.getRejected().get(0).getIndex()).isEqualTo(1);
    }

    private void seedActive() {
        redis.opsForSet().add(keys.entryIds(TID), EID);
        redis.opsForValue().set(keys.entry(TID, EID),
                "{\"entry_id\":\"" + EID + "\",\"name\":\"up-a\",\"weight\":100,\"order_no\":1,\"status\":\"ACTIVE\","
                        + "\"data_json\":{\"capacity\":{\"max_concurrency\":5,\"rate_limit_value\":5000,"
                        + "\"rate_window_ms\":1000,\"rate_unit\":\"TOKEN\"}}}");
    }
}
