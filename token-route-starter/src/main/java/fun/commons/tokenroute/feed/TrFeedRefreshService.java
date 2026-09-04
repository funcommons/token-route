package fun.commons.tokenroute.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.resolve.TrEntryId;
import fun.commons.tokenroute.resolve.TrFeedBackfill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FEED 拉取（01 §5.5，唯一条目写方）：三触发——
 * ① 惰性：resolve 侧 pullIfStale（条目列表龄 > refresh-interval，后台单飞）；
 * ② 立即：POST /v1/refresh/{tid}（TR-CTR-004，同步）；
 * ③ 冷启动/条目 miss：TrFeedBackfill.pull（同步 3s）。
 * 纪律：失败保旧值 + 连续失败 ≥3 告警；schema 非法 10633 本次拒收；单飞防击穿（本实例互斥 + Redis 游标龄判定）。
 */
public class TrFeedRefreshService implements TrFeedBackfill {

    private static final Logger log = LoggerFactory.getLogger(TrFeedRefreshService.class);
    static final long PULL_TIMEOUT_MS = 3_000;
    static final int MAX_DATA_JSON_BYTES = 8 * 1024;
    static final int FAIL_ALERT_THRESHOLD = 3;

    private final TrRedis redis;
    private final TrKeySpace keys;
    private final TrTableRegistry registry;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http;
    private final fun.commons.tokenroute.observe.TrMetrics metrics;
    /** 本实例单飞互斥（多实例靠 pull 语义幂等 + Redis 游标龄判定收敛） */
    private final ConcurrentHashMap<String, Object> inFlight = new ConcurrentHashMap<>();

    public TrFeedRefreshService(TrRedis redis, TrKeySpace keys, TrTableRegistry registry,
                                ObjectMapper mapper, Clock clock,
                                fun.commons.tokenroute.observe.TrMetrics metrics) {
        this.redis = redis;
        this.keys = keys;
        this.registry = registry;
        this.mapper = mapper;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(PULL_TIMEOUT_MS)).build();
        this.metrics = metrics;
    }

    /** 拉取结果（refresh 接口回显） */
    public record Result(boolean success, int pulled, int upserted, int removed, long elapsedMs, String error) {
    }

    @Override
    public boolean pull(String tableId) {
        return pullNow(tableId).success();
    }

    /** 冷启动/立即刷新：同步拉取（3s 超时）；结果计数（SLI#4） */
    public Result pullNow(String tableId) {
        Result r = pullOnce(tableId);
        metrics.feedPull(r.success());
        return r;
    }

    private Result pullOnce(String tableId) {
        long start = clock.millis();
        var tableOpt = registry.find(tableId);
        if (tableOpt.isEmpty()) {
            return new Result(false, 0, 0, 0, 0, "table_id 未注册");
        }
        String refreshUrl = tableOpt.get().getRefreshUrl();
        Object guard = inFlight.computeIfAbsent(tableId, k -> new Object());
        synchronized (guard) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(refreshUrl))
                        .timeout(Duration.ofMillis(PULL_TIMEOUT_MS)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return fail(tableId, start, "上游非 200: " + response.statusCode());
                }
                return ingest(tableId, response.body(), start);
            } catch (Exception e) {
                return fail(tableId, start, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                inFlight.remove(tableId);
            }
        }
    }

    /** 惰性触发①：条目列表龄超阈值 → 本线程单飞拉取（调用方已持旧值返回；resolve 侧在工作线程调用） */
    @Override
    public boolean pullIfStale(String tableId, long refreshIntervalSeconds) {
        String feedKey = keys.feed(tableId);
        Object lastPull = redis.stringTemplate().opsForHash().get(feedKey, "last_pull");
        long now = clock.millis();
        if (lastPull != null
                && now - Long.parseLong(String.valueOf(lastPull)) < refreshIntervalSeconds * 1000) {
            return false;
        }
        return pullNow(tableId).success();
    }

    /** schema 校验（02 §8）+ L5 全量对比落账 */
    private Result ingest(String tableId, String body, long start) throws Exception {
        Map<?, ?> payload = mapper.readValue(body, Map.class);
        Object entriesObj = payload == null ? null : payload.get("entries");
        if (!(entriesObj instanceof List<?> entries)) {
            return fail(tableId, start, "10633: 缺少 entries 数组");
        }
        List<String> args = new ArrayList<>();
        args.add(tableId);
        args.add(keysPrefix());
        int pulled = 0;
        for (Object o : entries) {
            if (!(o instanceof Map<?, ?> e)) {
                return fail(tableId, start, "10633: entries 元素非对象");
            }
            Object name = e.get("name");
            if (name == null || String.valueOf(name).isBlank()) {
                return fail(tableId, start, "10633: 坏 name（必填）");
            }
            Object status = e.get("status");
            if (status != null && !"ACTIVE".equals(status) && !"OFFLINE".equals(status)) {
                return fail(tableId, start, "10633: 非法 status " + status);
            }
            Map<String, Object> feedEntry = new HashMap<>();
            feedEntry.put("entry_id", TrEntryId.of(tableId, String.valueOf(name)));
            feedEntry.put("name", String.valueOf(name));
            feedEntry.put("weight", e.get("weight") instanceof Number ? e.get("weight") : 100);
            if (e.get("order_no") instanceof Number n) {
                feedEntry.put("order_no", n.intValue());
            }
            if (status != null) {
                feedEntry.put("status", status);
            }
            Object dataJson = e.get("data_json");
            feedEntry.put("data_json", dataJson instanceof Map<?, ?> dm ? dm : Map.of());
            String json = mapper.writeValueAsString(feedEntry);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DATA_JSON_BYTES) {
                return fail(tableId, start, "10633: 条目超上限（data_json ≤8KB）");
            }
            args.add(feedEntry.get("entry_id").toString());
            args.add(json);
            pulled++;
        }
        List<Object> counts = redis.stringTemplate().execute(TrLua.ENTRY_UPSERT,
                List.of(keys.entryIds(tableId)), args.toArray());
        markSuccess(tableId);
        long elapsed = clock.millis() - start;
        int added = counts == null ? 0 : ((Number) counts.get(0)).intValue();
        int updated = counts == null ? 0 : ((Number) counts.get(1)).intValue();
        int removed = counts == null ? 0 : ((Number) counts.get(2)).intValue();
        log.info("[TR-FEED] table={} pulled={} upserted={} removed={} elapsed_ms={}",
                tableId, pulled, added + updated, removed, elapsed);
        return new Result(true, pulled, added + updated, removed, elapsed, null);
    }

    private Result fail(String tableId, long start, String reason) {
        Long fails = redis.stringTemplate().opsForHash().increment(keys.feed(tableId), "fail_count", 1);
        if (fails != null && fails >= FAIL_ALERT_THRESHOLD) {
            log.error("[TR-FEED] 拉取连续失败 {} 次 table={} reason={}", fails, tableId, reason);
        } else {
            log.warn("[TR-FEED] 拉取失败（保旧值）table={} reason={}", tableId, reason);
        }
        return new Result(false, 0, 0, 0, clock.millis() - start, reason);
    }

    private void markSuccess(String tableId) {
        redis.stringTemplate().opsForHash().put(keys.feed(tableId),
                "last_pull", String.valueOf(clock.millis()));
        redis.stringTemplate().opsForHash().put(keys.feed(tableId), "fail_count", "0");
    }

    private String keysPrefix() {
        return keys.prefix();
    }
}
