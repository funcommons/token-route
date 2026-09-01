package fun.commons.tokenroute.report;

import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.common.TrCode;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrCapacity;
import fun.commons.tokenroute.resolve.TrEntry;
import fun.commons.tokenroute.resolve.TrRateUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * report 三态回填（01 §5.3 / 02 §6 TR-CTR-002）：
 * 逐条 L3 原子落账（精确释放 + 三态全记账 + 集中状态机信号）；
 * 未知 entry_id / 非法 lease → rejected 明细（10700）；聚合计数不幂等不去重。
 * 容量口径（窗口/单位桶）取自条目 data_json.capacity——与 resolve 预占同源。
 */
@Service
public class TrReportService {

    private static final Logger log = LoggerFactory.getLogger(TrReportService.class);
    private static final Set<String> VALID_RESULTS = Set.of("SUCCESS", "RETRYABLE_FAIL", "DISABLE_FAIL");
    /** default 预设连败冻结阈值（01 §4.2；channel-legacy 预设 P2） */
    private static final int DEFAULT_FAIL_THRESHOLD = 5;
    private static final long FREEZE_BASE_MS = 300_000L;
    private static final long FREEZE_CAP_MS = 7_200_000L;

    private final TrRedis redis;
    private final TrKeySpace keys;
    private final TrTableRegistry registry;
    private final Clock clock;
    private final fun.commons.tokenroute.observe.TrMetrics metrics;

    public TrReportService(TrRedis redis, TrKeySpace keys, TrTableRegistry registry, Clock clock,
                           fun.commons.tokenroute.observe.TrMetrics metrics) {
        this.redis = redis;
        this.keys = keys;
        this.registry = registry;
        this.clock = clock;
        this.metrics = metrics;
    }

    public TrReportResponse report(TrReportRequest request) {
        long start = clock.millis();
        if (request.getReports() == null || request.getReports().isEmpty()
                || request.getReports().size() > TrReportRequest.MAX_BATCH) {
            throw new ApiException(TrCode.PARAM_ERROR.getCode(),
                    "reports 批量须为 1~" + TrReportRequest.MAX_BATCH + " 条");
        }
        TrReportResponse response = new TrReportResponse();
        for (int i = 0; i < request.getReports().size(); i++) {
            TrReportRequest.Item item = request.getReports().get(i);
            String reject = validateItem(item);
            if (reject == null) {
                reject = aggregate(item);
            }
            if (reject != null) {
                response.getRejected().add(new TrReportResponse.Rejected(i, rejectCode(reject), reject));
                continue;
            }
            response.setAccepted(response.getAccepted() + 1);
        }
        metrics.report(start, response.getAccepted(), response.getRejected().size());
        return response;
    }

    /** 单条 L3 原子落账；返回 null=受理，非 null=拒收消息 */
    private String aggregate(TrReportRequest.Item item) {
        String tid = tableOf(item.getEntryId());
        if (tid == null) {
            return "未知 entry_id: " + item.getEntryId();
        }
        String raw = redis.stringTemplate().opsForValue().get(keys.entry(tid, item.getEntryId()));
        TrEntry entry = raw == null ? null : TrEntry.fromJson(raw);
        if (entry == null) {
            return "未知 entry_id: " + item.getEntryId();
        }
        TrCapacity cap = entry.capacity();
        boolean tokenBucket = cap != null && cap.rateLimitValue() != null && cap.rateUnit() != TrRateUnit.REQUEST;
        String window = String.valueOf(cap == null ? 1000L : cap.rateWindowOrDefault());
        try {
            List<Object> result = redis.stringTemplate().execute(TrLua.REPORT_AGGREGATE,
                    List.of(keys.conc(item.getEntryId()), keys.rateReq(item.getEntryId()),
                            keys.rateUnit(item.getEntryId()), keys.seq(item.getEntryId()),
                            keys.win(item.getEntryId()), keys.fail(item.getEntryId()),
                            keys.entry(tid, item.getEntryId()), keys.logState(item.getEntryId()),
                            keys.affinity(tid, item.getSessionId() == null ? "-" : item.getSessionId())),
                    item.getLeaseId(), item.getResult(),
                    String.valueOf(item.getRateUnits() == null ? 1.0 : item.getRateUnits()),
                    String.valueOf(clock.millis()), window, window,
                    tokenBucket ? (cap.rateUnit() == TrRateUnit.TOKEN ? "TOKEN" : "BIT") : "REQUEST",
                    String.valueOf(FREEZE_BASE_MS), String.valueOf(FREEZE_CAP_MS),
                    String.valueOf(DEFAULT_FAIL_THRESHOLD),
                    item.getSessionId() == null ? "" : item.getSessionId(),
                    "DISABLE_FAIL".equals(item.getResult()) ? "1" : "0");
            if (result != null && !result.isEmpty() && "REJECTED".equals(String.valueOf(result.get(0)))) {
                return "非法 lease（已回收/未知，迟到 report 无需补偿）";
            }
            if (result != null && !result.isEmpty() && "FROZEN".equals(String.valueOf(result.get(1)))) {
                log.info("[TR-STATE] entry={} to=FROZEN by={}", item.getEntryId(),
                        "DISABLE_FAIL".equals(item.getResult()) ? "REPORT_DISABLE" : "STATE_MACHINE");
            }
            return null;
        } catch (org.springframework.dao.DataAccessException e) {
            // Redis 故障：report 10700 部分/全拒（03 §6），消费方按补发纪律重投
            return "存储暂不可用，请按 10700 补发";
        }
    }

    /** entry_id 反查所属表：逐表 SISMEMBER（O(1)，表数量小；禁 SCAN，mc-cache 铁律） */
    private String tableOf(String entryId) {
        for (String tid : registry.tableIds()) {
            if (Boolean.TRUE.equals(redis.stringTemplate().opsForSet()
                    .isMember(keys.entryIds(tid), entryId))) {
                return tid;
            }
        }
        return null;
    }

    private int rejectCode(String message) {
        return message.startsWith("未知") ? TrCode.NOT_FOUND.getCode() : TrCode.PARAM_ERROR.getCode();
    }

    private String validateItem(TrReportRequest.Item item) {
        if (item.getEntryId() == null || item.getEntryId().isBlank()) {
            return "entry_id 必填";
        }
        if (item.getLeaseId() == null || item.getLeaseId().isBlank()) {
            return "lease_id 必填";
        }
        if (item.getResult() == null || !VALID_RESULTS.contains(item.getResult())) {
            return "result 仅允许 SUCCESS/RETRYABLE_FAIL/DISABLE_FAIL";
        }
        if (item.getRateUnits() != null && item.getRateUnits() < 0) {
            return "rate_units 不允许负数";
        }
        return null;
    }
}
