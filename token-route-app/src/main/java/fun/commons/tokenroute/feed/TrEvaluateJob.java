package fun.commons.tokenroute.feed;

import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.config.TrProperties;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * 评估器（01 §5.3：每分钟驱动降级爬升/一步恢复/惰性解冻；default 单预设判定矩阵）。
 * 多实例锁：Redisson tryLock(waitTime=0)（对齐 thmp SchedulerRunnerConfig 模板）；
 * Redisson 未配置时退化为本地语义（单实例部署；生产配置 Redisson 后自动升级多实例互斥）。
 */
@Component
public class TrEvaluateJob {

    private static final Logger log = LoggerFactory.getLogger(TrEvaluateJob.class);
    private static final int MIN_CALLS = 10;
    private static final double DEGRADE_RATIO = 0.5;
    private static final double RECOVER_RATIO = 0.1;

    private final TrRedis redis;
    private final TrKeySpace keys;
    private final TrTableRegistry registry;
    private final MultiRedisManager redisManager;
    private final String redisName;
    private final Clock clock;
    private final fun.commons.tokenroute.observe.TrMetrics metrics;

    public TrEvaluateJob(TrRedis redis, TrKeySpace keys, TrTableRegistry registry,
                         MultiRedisManager redisManager, TrProperties properties, Clock clock,
                         fun.commons.tokenroute.observe.TrMetrics metrics) {
        this.redis = redis;
        this.keys = keys;
        this.registry = registry;
        this.redisManager = redisManager;
        this.redisName = properties.getRedisName();
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evaluate() {
        long start = clock.millis();
        RedissonClient redisson = redisManager.getRedissonClient(redisName);
        RLock lock = redisson == null ? null : redisson.getLock(keys.schedLock("evaluator"));
        boolean locked = lock == null || lock.tryLock();
        if (!locked) {
            log.debug("评估器：他实例持锁，本实例跳过");
            return;
        }
        try {
            int moved = 0;
            int thawed = 0;
            for (var table : registry.all()) {
                if (!registry.isOnline(table.getName())) {
                    continue;
                }
                Set<String> ids = redis.stringTemplate().opsForSet().members(keys.entryIds(table.getName()));
                if (ids == null) {
                    continue;
                }
                for (String eid : ids) {
                    List<Object> r = redis.stringTemplate().execute(TrLua.EVALUATE_TRANSITION,
                            List.of(keys.entry(table.getName(), eid), keys.win(eid), keys.logState(eid)),
                            String.valueOf(clock.millis()), String.valueOf(MIN_CALLS),
                            String.valueOf(DEGRADE_RATIO), String.valueOf(RECOVER_RATIO));
                    if (r != null && !r.isEmpty()) {
                        String verdict = String.valueOf(r.get(0));
                        if ("MOVED".equals(verdict)) {
                            moved++;
                            metrics.stateTransition(String.valueOf(r.get(1)));
                            log.info("[TR-STATE] entry={} to={} calls={} fails={}", eid, r.get(1), r.get(2), r.get(3));
                        } else if ("THAWED".equals(verdict)) {
                            thawed++;
                            metrics.stateTransition("ACTIVE");
                            log.info("[TR-STATE] entry={} 惰性解冻 → ACTIVE", eid);
                        }
                    }
                }
            }
            if (moved + thawed > 0) {
                log.info("[TR-STATE] 评估完成 moved={} thawed={} elapsed_ms={}", moved, thawed, clock.millis() - start);
            }
            metrics.evaluatorRun();
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }
}
