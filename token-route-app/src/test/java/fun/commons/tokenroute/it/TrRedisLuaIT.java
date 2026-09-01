package fun.commons.tokenroute.it;

import fun.commons.tokenroute.redis.TrLua;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lua 原子操作 IT（03_数据设计 §3）：L1 resolve_bind / L2 affinity_hit / L6 lease_reclaim。
 * 需 Docker；surefire 默认不跑 *IT，显式 -Dtest=TrRedisLuaIT 或 -Dtr.it=true 启用。
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tr.it", matches = "true", disabledReason = "IT 需 Docker；-Dtr.it=true 显式启用")
class TrRedisLuaIT {

    private static final String REDIS_IMAGE = "docker.m.daocloud.io/library/redis:7-alpine";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    static StringRedisTemplate redis;
    static LettuceConnectionFactory factory;

    @BeforeAll
    static void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        factory.destroy();
    }

    private static final String AFF = "tr:affinity:llm:task-1";
    private static final String CONC = "tr:conc:e-1";
    private static final String ENTRY = "tr:entry:llm:e-1";
    private static final String REQ = "tr:rate:e-1:req";
    private static final String UNIT = "tr:rate:e-1:unit";
    private static final String SEQ = "tr:seq:e-1";

    private List<Object> l1(String entryId, String leaseId) {
        return redis.execute(TrLua.RESOLVE_BIND, List.of(AFF, CONC, REQ, UNIT, SEQ, "tr:log:aff:llm"),
                "task-1", entryId, "28800", leaseId, "30", "1000",
                "20", "-1", "1000", "-1", "1000", "{\"type\":\"BIND\",\"session\":\"task-1\"}", "604800", "1");
    }

    private List<Object> l2(String now, String leaseId) {
        return redis.execute(TrLua.AFFINITY_HIT, List.of(AFF, ENTRY, CONC, REQ, UNIT),
                now, "28800", leaseId, "30", "20", "-1", "1000", "-1", "1000");
    }

    @Test
    void l1BindNewThenLoserAdoptsWinner() {
        redis.delete(AFF);
        redis.delete(CONC);

        List<Object> first = l1("e-1", "lease-a");
        assertThat(first).containsExactly("NEW", "e-1");
        assertThat(redis.opsForValue().get(AFF)).isEqualTo("e-1");
        assertThat(redis.opsForZSet().size(CONC)).isEqualTo(1);
        assertThat(redis.opsForList().size("tr:log:aff:llm")).isGreaterThanOrEqualTo(1);

        // 并发同 session：败者采用胜者绑定，不产生第二个 lease
        List<Object> loser = l1("e-2", "lease-b");
        assertThat(loser).containsExactly("EXISTED", "e-1");
        assertThat(redis.opsForZSet().size(CONC)).isEqualTo(1);
        assertThat(redis.opsForZSet().score(CONC, "lease-b")).isNull();
    }

    @Test
    void l1RejectsWhenConcurrencyFull() {
        redis.delete(AFF);
        redis.delete(CONC);
        for (int i = 0; i < 20; i++) {
            redis.opsForZSet().add(CONC, "other-" + i, 2000);
        }
        List<Object> result = l1("e-1", "lease-a");
        assertThat(result).containsExactly("CAPACITY");
        assertThat(redis.opsForValue().get(AFF)).isNull();
    }

    @Test
    void l2HitRenewsAndLeases() {
        redis.delete(AFF);
        redis.delete(CONC);
        redis.opsForValue().set(AFF, "e-1", Duration.ofHours(8));
        redis.opsForValue().set(ENTRY, "{\"status\":\"ACTIVE\"}");

        List<Object> hit = l2("1000", "lease-c");
        assertThat(hit).containsExactly("HIT", "e-1");
        assertThat(redis.opsForZSet().score(CONC, "lease-c")).isEqualTo(31000.0);
        assertThat(redis.getExpire(AFF)).isGreaterThan(28790);
    }

    @Test
    void l2MissWhenEntryOutOfStatusDomain() {
        redis.delete(AFF);
        redis.delete(CONC);
        redis.opsForValue().set(AFF, "e-1", Duration.ofHours(8));
        redis.opsForValue().set(ENTRY, "{\"status\":\"OFFLINE\"}");

        List<Object> miss = l2("1000", "lease-c");
        assertThat(miss).containsExactly("MISS", "e-1");
        assertThat(redis.opsForZSet().size(CONC)).isZero();
    }

    @Test
    void l2MissWhenBindingGone() {
        redis.delete(AFF);
        redis.opsForValue().set(ENTRY, "{\"status\":\"ACTIVE\"}");
        List<Object> miss = l2("1000", "lease-c");
        assertThat(miss).containsExactly("MISS");
    }

    @Test
    void l2FrozenMissButThawsWhenWindowPassed() {
        redis.delete(AFF);
        redis.opsForValue().set(AFF, "e-1", Duration.ofHours(8));
        redis.opsForValue().set(ENTRY, "{\"status\":\"FROZEN\",\"frozen_until\":500}");

        // now=100 < frozen_until=500：冻结窗未满 → MISS
        List<Object> frozen = l2("100", "lease-c");
        assertThat(frozen).containsExactly("MISS", "e-1");

        // now=600 > frozen_until=500：窗满惰性解冻视同 ACTIVE（01 §4.2）
        List<Object> thawed = l2("600", "lease-d");
        assertThat(thawed).containsExactly("HIT", "e-1");
    }

    @Test
    void l6ReclaimsExpiredLeasesAndChargesRate() {
        redis.delete(CONC);
        redis.delete(REQ);
        redis.delete(UNIT);
        redis.delete(SEQ);
        redis.opsForZSet().add(CONC, "dead", 500);
        redis.opsForZSet().add(CONC, "alive", 100000);

        Long reclaimed = redis.execute(TrLua.LEASE_RECLAIM, List.of(CONC, REQ, UNIT, SEQ),
                "1000", "1000", "1000", "TOKEN");

        assertThat(reclaimed).isEqualTo(1);
        assertThat(redis.opsForZSet().score(CONC, "alive")).isEqualTo(100000.0);
        assertThat(redis.opsForZSet().score(CONC, "dead")).isNull();
        // 速率照计（保守 1 次 + 1 单位）
        assertThat(redis.opsForZSet().size(REQ)).isEqualTo(1);
        assertThat(redis.opsForZSet().size(UNIT)).isEqualTo(1);
    }

    @Test
    void l6RequestModeSkipsUnitBucket() {
        redis.delete(CONC);
        redis.delete(REQ);
        redis.delete(UNIT);
        redis.delete(SEQ);
        redis.opsForZSet().add(CONC, "dead", 500);

        Long reclaimed = redis.execute(TrLua.LEASE_RECLAIM, List.of(CONC, REQ, UNIT, SEQ),
                "1000", "1000", "1000", "REQUEST");

        assertThat(reclaimed).isEqualTo(1);
        assertThat(redis.opsForZSet().size(REQ)).isEqualTo(1);
        // REQUEST 口径 unit 桶不写（03 §2.2）
        assertThat(redis.opsForZSet().size(UNIT)).isZero();
    }
}
