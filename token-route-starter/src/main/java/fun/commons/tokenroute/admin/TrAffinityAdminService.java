package fun.commons.tokenroute.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrFeedBackfill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;


import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 亲和管理端点（issue #1，TR-ADM-001~004）：对既有亲和键 tr:affinity:{tid}:{session} 的
 * 内部运维写面——「零管理写面」原则的唯一例外。
 * 不动 resolve 流程（亲和判定位置 / 冻结让路语义不变）：upsert 即改流，下一 resolve 亲和命中新指向，
 * 指向条目 FROZEN/L3 时照常 miss 重选（自愈）。不动 FEED 数据面 / 表结构 / 键族。
 * 全操作审计日志 [TR-AFFINITY-ADMIN] + 亲和事件 ring（by=ADMIN_*，ops TR-OPS-003 可查）。
 */
public class TrAffinityAdminService {

    private static final Logger audit = LoggerFactory.getLogger("TR-AFFINITY-ADMIN");

    /** set 显式 TTL 边界（秒）：1 ~ 7d；缺省沿用表级 affinity_idle_timeout_seconds */
    public static final int TTL_MIN_SECONDS = 1;
    public static final int TTL_MAX_SECONDS = 604_800;
    /** list 单页上限（SCAN 无序，超限 complete=false → 收窄前缀重查） */
    public static final int LIST_DEFAULT_LIMIT = 100;
    public static final int LIST_MAX_LIMIT = 500;
    /** 亲和事件 ring：LTRIM 1000 + TTL 7d（03 §4，与 resolve 侧同规格） */
    private static final int AFF_LOG_TRIM = 1000;
    private static final Duration AFF_LOG_TTL = Duration.ofDays(7);
    private static final int SESSION_MAX_LEN = 128;

    private final TrRedis redis;
    private final TrKeySpace keys;
    private final TrTableRegistry registry;
    private final TrFeedBackfill backfill;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TrAffinityAdminService(TrRedis redis, TrKeySpace keys, TrTableRegistry registry,
                                  TrFeedBackfill backfill, ObjectMapper mapper, Clock clock) {
        this.redis = redis;
        this.keys = keys;
        this.registry = registry;
        this.backfill = backfill;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** TR-ADM-001 set：upsert 亲和指向（entry 存在性校验防 typo；状态不校验——冻结让路在 resolve 侧） */
    public Map<String, Object> set(String tid, String sessionId, String entryId, Integer ttlSeconds, String by) {
        TrTableDefinition table = requireAffinityTable(tid);
        requireSession(sessionId);
        if (ttlSeconds != null && (ttlSeconds < TTL_MIN_SECONDS || ttlSeconds > TTL_MAX_SECONDS)) {
            throw new ApiException(ApiCode.PARAM_ERROR,
                    "ttl_seconds 须在 " + TTL_MIN_SECONDS + "~" + TTL_MAX_SECONDS + " 秒之间");
        }
        requireEntry(tid, entryId);

        int ttl = ttlSeconds != null ? ttlSeconds : table.getAffinityIdleTimeoutSeconds();
        String key = keys.affinity(tid, sessionId);
        String prev = redis.stringTemplate().opsForValue().get(key);
        redis.stringTemplate().opsForValue().set(key, entryId, Duration.ofSeconds(ttl));
        affEvent(tid, "BIND", sessionId, entryId, "ADMIN_SET");
        audit.info("[TR-AFFINITY-ADMIN] op=set table={} session={} entry={} ttl_s={} prev={} by={}",
                tid, sessionId, entryId, ttl, prev == null ? "-" : prev, by);

        Map<String, Object> out = new HashMap<>();
        out.put("session_id", sessionId);
        out.put("entry_id", entryId);
        out.put("ttl_seconds", ttl);
        out.put("previous_entry_id", prev);
        return out;
    }

    /** TR-ADM-002 get：当前亲和指向 + 剩余 TTL */
    public Map<String, Object> get(String tid, String sessionId) {
        requireAffinityTable(tid);
        requireSession(sessionId);
        String key = keys.affinity(tid, sessionId);
        String value = redis.stringTemplate().opsForValue().get(key);
        if (value == null) {
            return Map.of("found", false);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("found", true);
        out.put("entry_id", value);
        out.put("ttl_remaining_seconds", ttlRemaining(key));
        return out;
    }

    /** TR-ADM-003 list：session_id 前缀 SCAN（运维低频排查；无序，超限收窄前缀） */
    public Map<String, Object> list(String tid, String sessionIdPrefix, Integer limit) {
        requireAffinityTable(tid);
        if (sessionIdPrefix != null && sessionIdPrefix.length() > SESSION_MAX_LEN) {
            throw new ApiException(ApiCode.PARAM_ERROR, "session_id_prefix 超长（≤128 字符）");
        }
        int cap = limit == null ? LIST_DEFAULT_LIMIT : Math.max(1, Math.min(limit, LIST_MAX_LIMIT));

        String pattern = keys.affinityScanPattern(tid, sessionIdPrefix);
        List<Map<String, Object>> items = new ArrayList<>();
        boolean complete = true;
        try (Cursor<String> cursor = redis.stringTemplate().scan(
                ScanOptions.scanOptions().match(pattern).count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String value = redis.stringTemplate().opsForValue().get(key);
                if (value == null) {
                    continue; // SCAN 与 GET 间过期（或野键），跳过
                }
                Map<String, Object> item = new HashMap<>();
                item.put("session_id", keys.sessionIdFromAffinityKey(tid, key));
                item.put("entry_id", value);
                item.put("ttl_remaining_seconds", ttlRemaining(key));
                items.add(item);
                if (items.size() >= cap) {
                    complete = false;
                    break;
                }
            }
        }
        items.sort(Comparator.comparing(m -> String.valueOf(m.get("session_id"))));

        Map<String, Object> out = new HashMap<>();
        out.put("table_id", tid);
        out.put("prefix", sessionIdPrefix);
        out.put("count", items.size());
        out.put("complete", complete);
        out.put("items", items);
        return out;
    }

    /** TR-ADM-004 delete：解除亲和（幂等，无绑定返回 deleted=false） */
    public Map<String, Object> delete(String tid, String sessionId, String by) {
        requireAffinityTable(tid);
        requireSession(sessionId);
        String key = keys.affinity(tid, sessionId);
        String prev = redis.stringTemplate().opsForValue().get(key);
        Boolean deleted = redis.stringTemplate().delete(key);
        boolean ok = Boolean.TRUE.equals(deleted);
        if (ok) {
            affEvent(tid, "DETACH", sessionId, prev, "ADMIN_DELETE");
        }
        audit.info("[TR-AFFINITY-ADMIN] op=delete table={} session={} prev={} by={} result={}",
                tid, sessionId, prev == null ? "-" : prev, by, ok ? "OK" : "NOOP");

        Map<String, Object> out = new HashMap<>();
        out.put("deleted", ok);
        out.put("entry_id", prev);
        return out;
    }

    private TrTableDefinition requireAffinityTable(String tid) {
        TrTableDefinition table = registry.find(tid)
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND, "table_id 未注册: " + tid));
        if (!table.isAffinityEnabled()) {
            throw new ApiException(ApiCode.PARAM_ERROR, "表未开启亲和: " + tid);
        }
        return table;
    }

    private void requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiException(ApiCode.PARAM_MISSING, "session_id 必填");
        }
        if (sessionId.length() > SESSION_MAX_LEN) {
            throw new ApiException(ApiCode.PARAM_ERROR, "session_id 超长（≤128 字符）");
        }
    }

    /** entry 存在性校验（防 typo）：条目键 miss → 回源一次再核，仍无 → 10400（01 §5.1 同语义） */
    private void requireEntry(String tid, String entryId) {
        String key = keys.entry(tid, entryId);
        if (redis.stringTemplate().opsForValue().get(key) != null) {
            return;
        }
        if (backfill.pull(tid) && redis.stringTemplate().opsForValue().get(key) != null) {
            return;
        }
        throw new ApiException(ApiCode.NOT_FOUND, "entry_id 不存在: " + entryId);
    }

    private Long ttlRemaining(String key) {
        Long ttl = redis.stringTemplate().getExpire(key, TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? null : ttl;
    }

    /** 亲和事件 ring（与 resolve 侧 BIND/DETACH 同形状，by 标记管理来源；失败不影响业务） */
    private void affEvent(String tid, String type, String sessionId, String entryId, String by) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "type", type, "session", sessionId,
                    "entry_id", entryId == null ? "" : entryId,
                    "at", clock.millis(), "by", by));
            redis.stringTemplate().opsForList().rightPush(keys.logAff(tid), json);
            redis.stringTemplate().opsForList().trim(keys.logAff(tid), -AFF_LOG_TRIM, -1);
            redis.stringTemplate().expire(keys.logAff(tid), AFF_LOG_TTL);
        } catch (Exception e) {
            audit.debug("亲和事件写入失败（不影响业务）: {}", e.getMessage());
        }
    }
}
