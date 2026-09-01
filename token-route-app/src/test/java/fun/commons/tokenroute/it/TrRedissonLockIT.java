package fun.commons.tokenroute.it;

import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.TrRouteApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⑤.2 Redisson 锁链路 IT：`framework4j.redis.datasources.<ds>.redisson.enabled=true` 使能后，
 * MultiRedisManager.getRedissonClient 返回可用客户端；跨线程 tryLock(0) 互斥成立——
 * 评估器（TrEvaluateJob）多实例部署依赖该语义（未使能时退化为单实例本地语义）。
 * 需 Docker；-Dtr.it=true 启用。
 */
@Testcontainers
@SpringBootTest(classes = TrRouteApplication.class, properties = {
        "framework4j.redis.datasources.main.redisson.enabled=true",
        "tr.scheduler.enabled=false"
})
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrRedissonLockIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("framework4j.redis.datasources.main.host", REDIS::getHost);
        r.add("framework4j.redis.datasources.main.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MultiRedisManager manager;

    @Test
    void redissonWiredWhenEnabled() {
        assertThat(manager.getRedissonClient("main")).isNotNull();
    }

    @Test
    void tryLockIsMutuallyExclusiveAcrossThreads() throws Exception {
        RedissonClient client = manager.getRedissonClient("main");
        RLock lock = client.getLock("tr:sched:lock:v1:evaluator");
        assertThat(lock.tryLock()).isTrue();
        try {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> other = pool.submit(() ->
                        client.getLock("tr:sched:lock:v1:evaluator").tryLock(0, TimeUnit.SECONDS));
                assertThat(other.get(5, TimeUnit.SECONDS)).isFalse(); // 跨线程立即失败 → 评估器他实例跳过
            } finally {
                pool.shutdownNow();
            }
        } finally {
            lock.unlock();
        }
        assertThat(lock.tryLock()).isTrue(); // 释放后可重得
        lock.unlock();
    }
}
