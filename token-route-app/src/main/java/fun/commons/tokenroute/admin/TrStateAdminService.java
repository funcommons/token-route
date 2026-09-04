package fun.commons.tokenroute.admin;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrFeedBackfill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态重置端点（TR-ADM-005，issue #1 追加）：运营手动把条目从状态机判定态
 * （DEGRADED_L1/L2/L3/FROZEN）即时复位 ACTIVE——余额不足停用类场景，
 * 充值完成后不等冻结退避窗/评估窗，手动立即恢复。
 * 语义边界：复位 = 全新开始（连败计数/1m 窗/退避升档 freeze_count 一并清零，
 * Lua 单往返原子执行）；OFFLINE 是 FEED 管理位，不经运营重置（上游显式 ACTIVE）；
 * report 三态照常驱动——复位后新失败信号仍会正常降级/冻结（无豁免）。
 * 审计：[TR-STATE-ADMIN] 结构化日志 + 状态迁移史 ring（reason=ADMIN_RESET）。
 */
@Service
public class TrStateAdminService {

    private static final Logger audit = LoggerFactory.getLogger("TR-STATE-ADMIN");

    private final TrRedis redis;
    private final TrKeySpace keys;
    private final TrTableRegistry registry;
    private final TrFeedBackfill backfill;
    private final Clock clock;

    public TrStateAdminService(TrRedis redis, TrKeySpace keys, TrTableRegistry registry,
                               TrFeedBackfill backfill, Clock clock) {
        this.redis = redis;
        this.keys = keys;
        this.registry = registry;
        this.backfill = backfill;
        this.clock = clock;
    }

    /** TR-ADM-005：条目状态即时复位 ACTIVE（幂等——已 ACTIVE 且无连败残留返回 reset=false） */
    public Map<String, Object> reset(String tid, String entryId, String by) {
        registry.find(tid)
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND, "table_id 未注册: " + tid));

        List<Object> r = executeReset(tid, entryId);
        if (r == null || r.isEmpty()) {
            throw new ApiException(ApiCode.NOT_FOUND, "条目数据不可读: " + entryId);
        }
        // 冷 Redis：条目键 miss → 回源一次再核（对齐亲和管理 set 的 entry 校验语义）
        if ("SKIP".equals(r.get(0)) && "NO_ENTRY".equals(r.get(1)) && backfill.pull(tid)) {
            r = executeReset(tid, entryId);
        }

        String verdict = String.valueOf(r.get(0));
        switch (verdict) {
            case "RESET" -> {
                String from = String.valueOf(r.get(1));
                audit.info("[TR-STATE-ADMIN] op=reset table={} entry={} from={} to=ACTIVE by={}",
                        tid, entryId, from, by);
                Map<String, Object> out = new HashMap<>();
                out.put("entry_id", entryId);
                out.put("previous_status", from);
                out.put("reset", true);
                return out;
            }
            case "NOOP" -> {
                audit.info("[TR-STATE-ADMIN] op=reset table={} entry={} from=ACTIVE by={} result=NOOP",
                        tid, entryId, by);
                Map<String, Object> out = new HashMap<>();
                out.put("entry_id", entryId);
                out.put("previous_status", "ACTIVE");
                out.put("reset", false);
                return out;
            }
            case "SKIP" -> {
                String reason = String.valueOf(r.get(1));
                if ("OFFLINE".equals(reason)) {
                    throw new ApiException(ApiCode.PARAM_ERROR,
                            "条目 OFFLINE 为 FEED 管理位，重置须上游显式 status=ACTIVE: " + entryId);
                }
                throw new ApiException(ApiCode.NOT_FOUND, "entry_id 不存在: " + entryId);
            }
            default -> throw new ApiException(ApiCode.NOT_FOUND, "条目数据不可读: " + entryId);
        }
    }

    private List<Object> executeReset(String tid, String entryId) {
        return redis.stringTemplate().execute(TrLua.STATE_RESET,
                List.of(keys.entry(tid, entryId), keys.fail(entryId), keys.win(entryId),
                        keys.logState(entryId)),
                String.valueOf(clock.millis()));
    }
}
