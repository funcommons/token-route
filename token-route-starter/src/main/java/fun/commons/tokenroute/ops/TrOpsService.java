package fun.commons.tokenroute.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrEntry;
import fun.commons.tokenroute.resolve.TrRateUnit;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运维只读查询面（02_接口契约 §7，TR-OPS-001~004；无写操作）。
 * ring 热窗 7d / LPTRIM 1000；亲和活跃数用限定前缀 SCAN（仅 ops 低频路径，业务面无扫描，mc-cache 铁律不破）。
 */
public class TrOpsService {

    private final TrRedis redis;
    private final TrKeySpace keys;
    private final TrTableRegistry registry;
    private final ObjectMapper mapper;

    public TrOpsService(TrRedis redis, TrKeySpace keys, TrTableRegistry registry, ObjectMapper mapper) {
        this.redis = redis;
        this.keys = keys;
        this.registry = registry;
        this.mapper = mapper;
    }

    /** TR-OPS-001：表元数据（config 回显）+ 条目实时状态 + 活跃亲和数 */
    public Map<String, Object> tableStatus(String tid) {
        TrTableDefinition table = registry.find(tid)
                .orElseThrow(() -> new IllegalArgumentException("table_id 未注册: " + tid));
        Map<String, Object> out = new HashMap<>();
        out.put("table_id", tid);
        out.put("tenant_id", table.getTenantId());
        out.put("strategy_type", table.getStrategyType().name());
        out.put("state_policy", table.getStatePolicy());
        out.put("data_profile", table.getDataProfile());
        out.put("refresh_url", table.getRefreshUrl());
        out.put("status", table.getStatus());
        out.put("metadata", table.getMetadata()); // 版本追溯元数据（issue #3 R2：策略系统发布标记原样回显）
        out.put("affinity_enabled", table.isAffinityEnabled());
        out.put("lease_ttl_seconds", table.getLeaseTtlSeconds());
        out.put("refresh_interval_seconds", table.getRefreshIntervalSeconds());

        List<Map<String, Object>> entries = new ArrayList<>();
        for (String eid : idsOf(tid)) {
            Map<String, Object> view = new HashMap<>();
            String json = redis.stringTemplate().opsForValue().get(keys.entry(tid, eid));
            TrEntry e = json == null ? null : TrEntry.fromJson(json);
            view.put("entry_id", eid);
            if (e == null) {
                view.put("status", "MISSING");
                entries.add(view);
                continue;
            }
            view.put("name", e.getName());
            view.put("status", e.getStatus());
            view.put("frozen_until", e.getFrozenUntil());
            view.put("weight_effective", e.effectiveWeight());
            view.put("consecutive_failures", redis.stringTemplate().opsForValue().get(keys.fail(eid)));
            // 并发占用（惰性回收过期后计数）+ 速率桶水位（窗口滚动后）
            redis.stringTemplate().opsForZSet().removeRangeByScore(keys.conc(eid),
                    Double.NEGATIVE_INFINITY, System.currentTimeMillis());
            view.put("conc_active", redis.stringTemplate().opsForZSet().size(keys.conc(eid)));
            var cap = e.capacity();
            long window = cap == null ? 1000L : cap.rateWindowOrDefault();
            view.put("rate_window_ms", window);
            view.put("rate_req_usage", windowUsage(eid, window, keys.rateReq(eid)));
            if (cap != null && cap.rateLimitValue() != null && cap.rateUnit() != fun.commons.tokenroute.resolve.TrRateUnit.REQUEST) {
                view.put("rate_unit", cap.rateUnit().name());
                view.put("rate_unit_usage", windowUsage(eid, window, keys.rateUnit(eid)));
                view.put("rate_limit_value", cap.rateLimitValue());
            }
            // 1m 滑动窗观测
            long now = System.currentTimeMillis();
            Set<String> win = redis.stringTemplate().opsForZSet()
                    .rangeByScore(keys.win(eid), now - 60_000, Double.POSITIVE_INFINITY);
            int calls = win.size();
            int fails = 0;
            for (String m : win) {
                if (m.endsWith(":fail")) {
                    fails++;
                }
            }
            view.put("win_calls", calls);
            view.put("win_failures", fails);
            entries.add(view);
        }
        out.put("entries", entries);
        out.put("affinity_active_count", affinityActiveCount(tid));
        return out;
    }

    /** TR-OPS-002：决议日志（ring 热窗；from/to 为 epoch ms，result=ok|empty 可选过滤） */
    public List<Object> resolveLogs(String tid, Long from, Long to, String result, int page, int size) {
        List<String> all = redis.stringTemplate().opsForList().range(keys.logResolve(tid), 0, -1);
        return filterPage(all, from, to, result, "result", page, size);
    }

    /** TR-OPS-003：亲和事件史（type=BIND|DETACH） */
    public List<Object> affinityEvents(String tid, String type, int page, int size) {
        List<String> all = redis.stringTemplate().opsForList().range(keys.logAff(tid), 0, -1);
        return filterPage(all, null, null, type, "type", page, size);
    }

    /** TR-OPS-004：状态迁移史 */
    public List<Object> stateLogs(String entryId, int page, int size) {
        List<String> all = redis.stringTemplate().opsForList().range(keys.logState(entryId), 0, -1);
        return filterPage(all, null, null, null, "at", page, size);
    }

    /** ring 行为 JSON 串——解析为对象返回（前端直接消费），过滤后分页 */
    private List<Object> filterPage(List<String> all, Long from, Long to, String tag, String tagField,
                                    int page, int size) {
        List<Object> filtered = new ArrayList<>();
        if (all == null) {
            return filtered;
        }
        for (String line : all) {
            Map<?, ?> m = toMap(line);
            if (from != null && num(m.get("at")) < from) {
                continue;
            }
            if (to != null && num(m.get("at")) > to) {
                continue;
            }
            if (tag != null && !tag.isBlank() && !tag.equals(String.valueOf(m.get(tagField)))) {
                continue;
            }
            filtered.add(m);
        }
        int start = Math.max(0, (page - 1) * size);
        if (start >= filtered.size()) {
            return List.of();
        }
        return filtered.subList(start, Math.min(filtered.size(), start + size));
    }

    private List<String> idsOf(String tid) {
        var ids = redis.stringTemplate().opsForSet().members(keys.entryIds(tid));
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    /** 限定前缀 SCAN 计数（仅 ops 只读低频路径；count 分批 500） */
    private long affinityActiveCount(String tid) {
        long n = 0;
        try (Cursor<String> cursor = redis.stringTemplate().scan(
                ScanOptions.scanOptions().match(keys.affinity(tid, "*") + "").count(500).build())) {
            while (cursor.hasNext()) {
                cursor.next();
                n++;
            }
        }
        return n;
    }

    private long windowUsage(String eid, long windowMs, String key) {
        redis.stringTemplate().opsForZSet().removeRangeByScore(key,
                Double.NEGATIVE_INFINITY, System.currentTimeMillis() - windowMs);
        Long size = redis.stringTemplate().opsForZSet().size(key);
        return size == null ? 0 : size;
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> toMap(String line) {
        try {
            return mapper.readValue(line, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private long num(Object v) {
        return v instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

}
