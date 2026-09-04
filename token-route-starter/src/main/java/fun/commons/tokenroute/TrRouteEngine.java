package fun.commons.tokenroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.report.TrReportRequest;
import fun.commons.tokenroute.report.TrReportResponse;
import fun.commons.tokenroute.report.TrReportService;
import fun.commons.tokenroute.resolve.TrResolveRequest;
import fun.commons.tokenroute.resolve.TrResolveResponse;
import fun.commons.tokenroute.resolve.TrResolveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;

/**
 * 进程内路由门面（嵌入式 SDK 主入口，docs/用户文档/03_嵌入式SDK接入指南.md）：
 * resolve / report / detach 三操作直调内核，零网络跳（01 §7.2 嵌入式形态）。
 * <ul>
 *   <li>lease_id 纪律与 HTTP 模式完全相同（02 §2.2）：resolve → 真实上游调用 → report 回传释放；</li>
 *   <li>detach（TR-CTR-003 内核实现）：幂等解除会话亲和——DEL 绑定 + DETACH 亲和事件 ring；</li>
 *   <li>管理能力不进门面：宿主直接注入 {@link fun.commons.tokenroute.admin.TrAffinityAdminService}
 *       / {@link fun.commons.tokenroute.admin.TrStateAdminService}（进程内无鉴权面）。</li>
 * </ul>
 */
public class TrRouteEngine {

    private static final Logger log = LoggerFactory.getLogger(TrRouteEngine.class);
    private static final long AFF_LOG_TTL_SECONDS = 604_800;
    private static final int AFF_LOG_TRIM = 1000;

    private final TrResolveService resolveService;
    private final TrReportService reportService;
    private final TrRedis redis;
    private final TrKeySpace keys;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TrRouteEngine(TrResolveService resolveService, TrReportService reportService,
                         TrRedis redis, TrKeySpace keys, ObjectMapper mapper, Clock clock) {
        this.resolveService = resolveService;
        this.reportService = reportService;
        this.redis = redis;
        this.keys = keys;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 取路由 + 并发预占（EMPTY 语义同 02 §6：entry_id=null 不抛错） */
    public TrResolveResponse resolve(TrResolveRequest request) {
        return resolveService.resolve(request, null);
    }

    /** 取路由便捷重载（caller 归因可传宿主服务名，落决议日志） */
    public TrResolveResponse resolve(String tableId, String sessionId, Map<String, Object> bizParams,
                                      String callerId) {
        TrResolveRequest request = new TrResolveRequest();
        request.setTableId(tableId);
        request.setSessionId(sessionId);
        request.setBizParams(bizParams);
        return resolveService.resolve(request, callerId);
    }

    /** 结果回填（三态；批量 ≤100；10700 语义见 02 §6 TR-CTR-002） */
    public TrReportResponse report(TrReportRequest request) {
        return reportService.report(request);
    }

    /** 亲和解除（幂等）：无绑定返回 false；成功 DEL + DETACH 亲和事件（by=CONSUMER，TR-OPS-003 可查） */
    public boolean detach(String tableId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session_id 必填");
        }
        String key = keys.affinity(tableId, sessionId);
        String prev = redis.stringTemplate().opsForValue().get(key);
        Boolean deleted = redis.stringTemplate().delete(key);
        boolean ok = Boolean.TRUE.equals(deleted);
        if (ok) {
            affEvent(tableId, sessionId, prev);
            log.info("[TR-AFFINITY] detach table={} session={} entry={}", tableId, sessionId, prev);
        }
        return ok;
    }

    /** 亲和事件 ring（与 resolve/管理面同形状；失败不影响业务） */
    private void affEvent(String tableId, String sessionId, String prev) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "type", "DETACH", "session", sessionId,
                    "entry_id", prev == null ? "" : prev,
                    "at", clock.millis(), "by", "CONSUMER"));
            redis.stringTemplate().opsForList().rightPush(keys.logAff(tableId), json);
            redis.stringTemplate().opsForList().trim(keys.logAff(tableId), -AFF_LOG_TRIM, -1);
            redis.stringTemplate().expire(keys.logAff(tableId), java.time.Duration.ofSeconds(AFF_LOG_TTL_SECONDS));
        } catch (Exception e) {
            log.debug("亲和事件写入失败（不影响业务）: {}", e.getMessage());
        }
    }
}
