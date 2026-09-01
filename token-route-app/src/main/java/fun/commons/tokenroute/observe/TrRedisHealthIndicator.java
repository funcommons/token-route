package fun.commons.tokenroute.observe;

import fun.commons.tokenroute.redis.TrRedis;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Redis 连通性健康探测（SLI#8）：/actuator/health 组件键 tokenRouteRedis（bean 由 TrConfig 显式命名，
 * 避免 Boot 对 xxxHealthIndicator 的推断键与业务 bean 'trRedis' 冲突）。
 * 沿用 MultiRedisManager.checkHealth（3s 命令超时）；down 不影响 resolve EMPTY / report 10700 降级语义。
 * 注意 /actuator/** 不在两面鉴权路径（/v1/**）内，生产以网络隔离或独立 management 端口收敛暴露面。
 */
public class TrRedisHealthIndicator implements HealthIndicator {

    private final TrRedis redis;

    public TrRedisHealthIndicator(TrRedis redis) {
        this.redis = redis;
    }

    @Override
    public Health health() {
        try {
            return redis.healthy()
                    ? Health.up().build()
                    : Health.down().withDetail("datasource", "main").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
