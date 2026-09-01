package fun.commons.tokenroute.resolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.engine.TrCompiledScript;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptExecutionException;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * resolve 决策全流程（01 §5.1）：
 * 三层取数（表壳内存 → entry-ids/entry 键回源缓存 → 运行时状态）→ 过滤链（态域 + 容量 + filter_script）
 * → 亲和判定（L2 双校验）→ 策略选择（四策略）→ 原子落账（L1 绑定/预占）→ 返回/EMPTY。
 * Redis 故障 → EMPTY 不误动作（03 §6）；[TR-RESOLVE] 结构化日志 + ring 热窗。
 */
@Service
public class TrResolveService {

    private static final Logger log = LoggerFactory.getLogger(TrResolveService.class);
    private static final Logger scriptLog = LoggerFactory.getLogger("TR-SCRIPT");

    private final TrRedis redis;
    private final TrKeySpace keys;
    private final TrTableRegistry registry;
    private final TrScriptRegistry scripts;
    private final TrScriptEngine engine;
    private final TrFeedBackfill backfill;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final fun.commons.tokenroute.observe.TrMetrics metrics;

    /** SLI：script_degraded_total（05 §7） */
    private final AtomicLong scriptDegradedTotal = new AtomicLong();

    /** 惰性 FEED 刷新后台执行器（daemon，不阻塞 resolve） */
    private final java.util.concurrent.ExecutorService lazyRefresher =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tr-lazy-feed");
                t.setDaemon(true);
                return t;
            });

    public TrResolveService(TrRedis redis, TrKeySpace keys, TrTableRegistry registry,
                            TrScriptRegistry scripts, TrScriptEngine engine,
                            TrFeedBackfill backfill, ObjectMapper mapper, Clock clock,
                            fun.commons.tokenroute.observe.TrMetrics metrics) {
        this.redis = redis;
        this.keys = keys;
        this.registry = registry;
        this.scripts = scripts;
        this.engine = engine;
        this.backfill = backfill;
        this.mapper = mapper;
        this.clock = clock;
        this.metrics = metrics;
        metrics.gauge("tr.script.degraded", scriptDegradedTotal);
    }

    public long scriptDegradedTotal() {
        return scriptDegradedTotal.get();
    }

    public TrResolveResponse resolve(TrResolveRequest req, String callerId) {
        long start = clock.millis();
        String tid = req.getTableId();
        TrTableDefinition table = registry.find(tid)
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND, "table_id 未注册: " + tid));

        TrResolveResponse resp = doResolve(table, req);
        writeResolveLog(table, req, callerId, resp, start);
        metrics.resolve(start, resp.getEntryId() == null);
        log.info("[TR-RESOLVE] table={} session={} caller={} entry={} affinity={} reasons={} elapsed_ms={}",
                tid, req.getSessionId(), callerId,
                resp.getEntryId(), resp.getAffinity(), resp.getReasons(), clock.millis() - start);
        return resp;
    }

    private TrResolveResponse doResolve(TrTableDefinition table, TrResolveRequest req) {
        String tid = table.getName();
        long now = clock.millis();
        // 注册但 OFFLINE → EMPTY + TABLE_OFFLINE（02 §6）
        if (!registry.isOnline(tid)) {
            return TrResolveResponse.empty(List.of(TrResolveResponse.R_TABLE_OFFLINE));
        }
        try {
            return resolveInRedis(table, req, now);
        } catch (org.springframework.dao.DataAccessException e) {
            // Redis 故障（连接失败/命令超时）→ resolve EMPTY 不误动作（03 §6）；表壳在内存不受影响
            log.warn("[TR-RESOLVE] redis-error table={} session={}", tid, req.getSessionId(), e);
            return TrResolveResponse.empty(List.of());
        }
    }

    private TrResolveResponse resolveInRedis(TrTableDefinition table, TrResolveRequest req, long now) {
        String tid = table.getName();
        StringRedisTemplate t = redis.stringTemplate();

        // —— 三层取数（01 §5.1 第 1 步）——
        String idsKey = keys.entryIds(tid);
        Set<String> ids = loadEntryIds(t, tid, idsKey, table.getRefreshIntervalSeconds());
        if (ids == null) {
            return TrResolveResponse.empty(List.of(TrResolveResponse.R_TABLE_EMPTY));
        }

        List<TrEntry> entries = loadEntries(t, tid, ids, now);
        // 态域过滤（{ACTIVE, L1} 参与；FROZEN 过期惰性回）
        List<TrEntry> candidates = entries.stream().filter(e -> e.selectable(now)).toList();

        // —— 过滤链：filter_script 谓词（参数 × entry.data_json，逐条目求值）——
        long degradedBefore = scriptDegradedTotal.get();
        candidates = applyFilterScript(table, req, candidates);

        List<String> reasons = new ArrayList<>();

        // —— 亲和判定（表开亲和且 session 非空；L2 态域 + 容量双校验）——
        boolean useAffinity = table.isAffinityEnabled()
                && req.getSessionId() != null && !req.getSessionId().isBlank();
        if (useAffinity) {
            String boundEid = t.opsForValue().get(keys.affinity(tid, req.getSessionId()));
            if (boundEid != null) {
                String boundJson = t.opsForValue().get(keys.entry(tid, boundEid));
                TrEntry bound = boundJson == null ? null : TrEntry.fromJson(boundJson);
                if (bound != null) {
                    TrCapacity cap = bound.capacity();
                    String leaseId = UUID.randomUUID().toString();
                    List<Object> hit = t.execute(TrLua.AFFINITY_HIT,
                            List.of(keys.affinity(tid, req.getSessionId()), keys.entry(tid, boundEid),
                                    keys.conc(boundEid), keys.rateReq(boundEid), keys.rateUnit(boundEid)),
                            String.valueOf(now), String.valueOf(table.getAffinityIdleTimeoutSeconds()),
                            leaseId, String.valueOf(table.getLeaseTtlSeconds()),
                            cap != null && cap.maxConcurrency() != null ? String.valueOf(cap.maxConcurrency()) : "-1",
                            cap != null && cap.rateLimitValue() != null && cap.rateUnit() == TrRateUnit.REQUEST
                                    ? String.valueOf(cap.rateLimitValue()) : "-1",
                            String.valueOf(cap == null ? 1000L : cap.rateWindowOrDefault()),
                            cap != null && cap.rateLimitValue() != null && cap.rateUnit() != TrRateUnit.REQUEST
                                    ? String.valueOf(cap.rateLimitValue()) : "-1",
                            String.valueOf(cap == null ? 1000L : cap.rateWindowOrDefault()));
                    String verdict = hit == null || hit.isEmpty() ? "MISS" : String.valueOf(hit.get(0));
                    if ("HIT".equals(verdict)) {
                        TrResolveResponse resp = new TrResolveResponse();
                        resp.setEntryId(boundEid);
                        resp.setDataJson(bound.getDataJson());
                        resp.setLeaseId(leaseId);
                        resp.setAffinity(TrResolveResponse.AFF_HIT);
                        return resp;
                    }
                    if ("CAPACITY".equals(verdict)) {
                        reasons.add(TrResolveResponse.R_CAPACITY); // 亲和条目满载 → 视同脱离域重绑（01 §4.2）
                    }
                    // MISS → 走策略并重新绑定
                }
            }
        }

        // —— 策略选择 + L1 原子预占（满载条目出局重试，至多候选数轮）——
        List<TrEntry> pool = new ArrayList<>(candidates);
        int round = 0;
        while (!pool.isEmpty() && round++ <= candidates.size()) {
            TrEntry selected = select(table, req, pool, now);
            if (selected == null) {
                break;
            }
            String leaseId = UUID.randomUUID().toString();
            List<Object> result = leaseAndBind(table, req, selected, leaseId, now);
            String verdict = result == null || result.isEmpty() ? "CAPACITY" : String.valueOf(result.get(0));
            switch (verdict) {
                case "NEW" -> {
                    return success(selected, leaseId,
                            useAffinity ? TrResolveResponse.AFF_NEW : TrResolveResponse.AFF_NONE);
                }
                case "EXISTED" -> {
                    // 并发同 session：败者采用胜者绑定（等价 HIT 返回，不重跑策略）
                    String winner = String.valueOf(result.get(1));
                    TrEntry bound = findById(entries, winner);
                    if (bound != null) {
                        TrResolveResponse resp = new TrResolveResponse();
                        resp.setEntryId(winner);
                        resp.setDataJson(bound.getDataJson());
                        resp.setAffinity(TrResolveResponse.AFF_HIT);
                        return resp;
                    }
                }
                case "CAPACITY" -> {
                    reasons.add(TrResolveResponse.R_CAPACITY);
                    pool.remove(selected); // 满载条目对新会话出局（01 §5.2）
                }
                default -> pool.remove(selected);
            }
        }

        if (pool.isEmpty() && !candidates.isEmpty() && reasons.contains(TrResolveResponse.R_CAPACITY)) {
            return TrResolveResponse.empty(List.of(TrResolveResponse.R_CAPACITY));
        }
        // 脚本降级计数增量（filter/selector 求值异常）→ 原因码随 EMPTY 返回（02 §6）
        if (scriptDegradedTotal.get() > degradedBefore) {
            reasons.add(TrResolveResponse.R_SCRIPT_DEGRADED);
        }
        if (candidates.isEmpty() && reasons.isEmpty()) {
            reasons.add(TrResolveResponse.R_ALL_FILTERED);
        }
        return TrResolveResponse.empty(List.copyOf(new HashSet<>(reasons)));
    }

    /** 键不存在 → 单飞同步回源一次；仍不存在 = 冷启动失败；存在（含空集）返回集合。
     *  惰性触发①：键存在时若列表龄超刷新周期，后台单飞拉取，调用方继续用旧值（01 §5.5） */
    private Set<String> loadEntryIds(StringRedisTemplate t, String tid, String idsKey, long refreshIntervalSeconds) {
        boolean keyExists = Boolean.TRUE.equals(t.hasKey(idsKey));
        if (!keyExists) {
            boolean ok = backfill.pull(tid);
            if (!ok && !Boolean.TRUE.equals(t.hasKey(idsKey))) {
                return null; // 冷启动失败 → TABLE_EMPTY
            }
        } else {
            java.util.concurrent.CompletableFuture.runAsync(
                    () -> backfill.pullIfStale(tid, refreshIntervalSeconds), lazyRefresher);
        }
        Set<String> members = t.opsForSet().members(idsKey);
        return members == null ? Set.of() : members;
    }

    /** MGET 条目；单条目 miss → 同步回源一次（单飞），失败按无此条目（01 §5.1） */
    private List<TrEntry> loadEntries(StringRedisTemplate t, String tid, Set<String> ids, long now) {
        List<String> idList = new ArrayList<>(ids);
        List<String> jsons = t.opsForValue().multiGet(idList.stream().map(id -> keys.entry(tid, id)).toList());
        List<TrEntry> entries = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < idList.size(); i++) {
            String json = jsons == null ? null : jsons.get(i);
            TrEntry e = json == null ? null : TrEntry.fromJson(json);
            if (e == null) {
                missing.add(idList.get(i));
            } else {
                entries.add(e);
            }
        }
        if (!missing.isEmpty() && backfill.pull(tid)) {
            jsons = t.opsForValue().multiGet(missing.stream().map(id -> keys.entry(tid, id)).toList());
            for (int i = 0; i < missing.size(); i++) {
                String json = jsons == null ? null : jsons.get(i);
                if (json != null) {
                    TrEntry e = TrEntry.fromJson(json);
                    if (e != null) {
                        entries.add(e);
                    }
                }
            }
        }
        return entries;
    }

    /** filter_script 谓词（05 §4）：true 留 / false 或非 Boolean 出局；求值异常 → 该条目出局 + [TR-SCRIPT] 至多一次 */
    private List<TrEntry> applyFilterScript(TrTableDefinition table, TrResolveRequest req, List<TrEntry> candidates) {
        Optional<TrCompiledScript> filter = scripts.find(table.getName() + TrScriptRegistry.FILTER_SUFFIX);
        if (filter.isEmpty() || candidates.isEmpty()) {
            return candidates;
        }
        boolean warned = false;
        List<TrEntry> kept = new ArrayList<>();
        for (TrEntry e : candidates) {
            try {
                if (engine.evalFilter(filter.get(), paramsOf(req), entryView(e), clock.millis())) {
                    kept.add(e);
                }
            } catch (TrScriptExecutionException ex) {
                if (!warned) {
                    scriptLog.warn("[TR-SCRIPT] filter degrade table={} entry={} err={}",
                            table.getName(), e.getEntryId(), ex.getMessage());
                    warned = true;
                }
                scriptDegradedTotal.incrementAndGet();
            }
        }
        return kept;
    }

    /** 四策略（01 §5.1 第 4 步）；SCRIPT 选择器非法/异常 → 回退加权随机（05 §4） */
    private TrEntry select(TrTableDefinition table, TrResolveRequest req, List<TrEntry> pool, long now) {
        return switch (table.getStrategyType()) {
            case WEIGHTED_RANDOM -> TrSelection.weightedRandom(pool);
            case ROUND_ROBIN -> {
                Long cursor = redis.stringTemplate().opsForValue().increment(keys.rr(table.getName()));
                yield TrSelection.roundRobin(pool, cursor == null ? 1 : cursor);
            }
            case WEIGHT_FIRST -> TrSelection.weightFirst(pool);
            case SCRIPT -> {
                Optional<TrCompiledScript> selector =
                        scripts.find(table.getName() + TrScriptRegistry.SELECTOR_SUFFIX);
                if (selector.isPresent()) {
                    try {
                        List<Map<String, Object>> views = pool.stream().map(this::entryView).toList();
                        Optional<String> id = engine.evalSelector(selector.get(), paramsOf(req), views, now);
                        if (id.isPresent()) {
                            TrEntry picked = findById(pool, id.get());
                            if (picked != null) {
                                yield picked;
                            }
                        }
                    } catch (TrScriptExecutionException ex) {
                        scriptLog.warn("[TR-SCRIPT] selector degrade table={} err={}", table.getName(), ex.getMessage());
                        scriptDegradedTotal.incrementAndGet();
                    }
                }
                yield TrSelection.weightedRandom(pool); // 回退表默认策略（加权随机）
            }
        };
    }

    /** L1 原子落账：容量校验 + 并发预占（+ 亲和绑定/事件 log） */
    private List<Object> leaseAndBind(TrTableDefinition table, TrResolveRequest req,
                                      TrEntry entry, String leaseId, long now) {
        TrCapacity cap = entry.capacity();
        boolean bind = table.isAffinityEnabled()
                && req.getSessionId() != null && !req.getSessionId().isBlank();
        String reqLimit = (cap != null && cap.rateLimitValue() != null && cap.rateUnit() == TrRateUnit.REQUEST)
                ? String.valueOf(cap.rateLimitValue()) : "-1";
        String unitLimit = (cap != null && cap.rateLimitValue() != null && cap.rateUnit() != TrRateUnit.REQUEST)
                ? String.valueOf(cap.rateLimitValue()) : "-1";
        String window = String.valueOf(cap == null ? 1000L : cap.rateWindowOrDefault());
        String maxConc = cap != null && cap.maxConcurrency() != null ? String.valueOf(cap.maxConcurrency()) : "-1";
        return redis.stringTemplate().execute(TrLua.RESOLVE_BIND,
                List.of(keys.affinity(table.getName(), req.getSessionId() == null ? "-" : req.getSessionId()),
                        keys.conc(entry.getEntryId()), keys.rateReq(entry.getEntryId()),
                        keys.rateUnit(entry.getEntryId()), keys.seq(entry.getEntryId()),
                        keys.logAff(table.getName())),
                req.getSessionId() == null ? "-" : req.getSessionId(),
                entry.getEntryId(),
                String.valueOf(table.getAffinityIdleTimeoutSeconds()),
                leaseId,
                String.valueOf(table.getLeaseTtlSeconds()),
                String.valueOf(now),
                maxConc, reqLimit, window, unitLimit, window,
                affEventJson(table, req, entry),
                "604800",
                bind ? "1" : "0");
    }

    private String affEventJson(TrTableDefinition table, TrResolveRequest req, TrEntry entry) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "type", "BIND", "session", req.getSessionId() == null ? "" : req.getSessionId(),
                    "entry_id", entry.getEntryId(), "at", clock.millis(), "by", "RESOLVE"));
        } catch (Exception e) {
            return "{\"type\":\"BIND\"}";
        }
    }

    private TrResolveResponse success(TrEntry entry, String leaseId, String affinity) {
        TrResolveResponse resp = new TrResolveResponse();
        resp.setEntryId(entry.getEntryId());
        resp.setDataJson(entry.getDataJson());
        resp.setLeaseId(leaseId);
        resp.setAffinity(affinity);
        return resp;
    }

    private TrEntry findById(List<TrEntry> entries, String entryId) {
        return entries.stream().filter(e -> entryId.equals(e.getEntryId())).findFirst().orElse(null);
    }

    /** 脚本可见单条目视图（05 §3）：{entry_id, name, weight, order_no, status, data_json…} */
    private Map<String, Object> entryView(TrEntry e) {
        Map<String, Object> view = new HashMap<>();
        view.put("entry_id", e.getEntryId());
        view.put("name", e.getName());
        view.put("weight", e.getWeight());
        view.put("order_no", e.getOrderNo());
        view.put("status", e.getStatus());
        view.put("data_json", e.getDataJson());
        return view;
    }

    private Map<String, Object> paramsOf(TrResolveRequest req) {
        return req.getBizParams() == null ? Map.of() : req.getBizParams();
    }

    /** 决议日志 ring（03 §2.3：LPTRIM 1000 + TTL 7d；全量走结构化日志 → Loki） */
    private void writeResolveLog(TrTableDefinition table, TrResolveRequest req, String callerId,
                                 TrResolveResponse resp, long start) {
        try {
            String line = mapper.writeValueAsString(Map.of(
                    "at", clock.millis(), "table", table.getName(),
                    "session", req.getSessionId() == null ? "" : req.getSessionId(),
                    "caller", callerId == null ? "" : callerId,
                    "entry_id", resp.getEntryId() == null ? "" : resp.getEntryId(),
                    "affinity", resp.getAffinity() == null ? "" : resp.getAffinity(),
                    "empty", resp.getEntryId() == null,
                    "reasons", resp.getReasons(),
                    "elapsed_ms", clock.millis() - start));
            String key = keys.logResolve(table.getName());
            redis.stringTemplate().opsForList().rightPush(key, line);
            redis.stringTemplate().opsForList().trim(key, -1000, -1);
            redis.stringTemplate().expire(key, java.time.Duration.ofDays(7));
        } catch (Exception e) {
            log.debug("决议日志写入失败（不影响业务）: {}", e.getMessage());
        }
    }
}
