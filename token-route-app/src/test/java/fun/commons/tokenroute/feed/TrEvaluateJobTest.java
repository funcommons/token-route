package fun.commons.tokenroute.feed;

import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.config.TrProperties;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrLua;
import fun.commons.tokenroute.redis.TrRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评估器分支（01 §5.3）：多实例锁竞争跳过 / 无 Redisson 本地语义 / 表离线跳过 /
 * 空集合跳过 / MOVED 与 THAWED 计数 / 其他裁决忽略 / finally 解锁。
 */
class TrEvaluateJobTest {

    private static final long NOW = 1_700_000_000_000L;

    private final TrKeySpace keys = new TrKeySpace("tr");
    private StringRedisTemplate t;
    private MultiRedisManager manager;
    private TrTableRegistry registry;

    @BeforeEach
    void setUp() {
        TrRedis redis = mock(TrRedis.class);
        t = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.stringTemplate()).thenReturn(t);
        manager = mock(MultiRedisManager.class);
        registry = TrTableRegistry.load(List.of(table("t1", "ACTIVE"), table("t2", "OFFLINE")));
    }

    private static TrTableDefinition table(String name, String status) {
        TrTableDefinition d = new TrTableDefinition();
        d.setName(name);
        d.setRefreshUrl("http://feed.example/" + name);
        d.setStatus(status);
        return d;
    }

    private TrEvaluateJob job() {
        return new TrEvaluateJob(mockRedis(), keys, registry, manager, props(), fixedClock());
    }

    private TrRedis mockRedis() {
        TrRedis redis = mock(TrRedis.class);
        when(redis.stringTemplate()).thenReturn(t);
        return redis;
    }

    private TrProperties props() {
        return new TrProperties();
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    }

    private void entriesOf(String table, Set<String> ids) {
        when(t.opsForSet().members(keys.entryIds(table))).thenReturn(ids);
    }

    private void verdictByEntry(String movedEid) {
        when(t.execute(same(TrLua.EVALUATE_TRANSITION), anyList(), any(Object[].class))).thenAnswer(inv -> {
            List<Object> ks = inv.getArgument(1);
            String entryKey = String.valueOf(ks.get(0));
            return entryKey.endsWith(movedEid)
                    ? List.of("MOVED", "DEGRADED_L1", "12", "3")
                    : List.of("THAWED");
        });
    }

    @Test
    void countsMovedAndThawedAcrossTables() {
        entriesOf("t1", new LinkedHashSet<>(List.of("e1", "e2")));
        verdictByEntry("e1");
        job().evaluate(); // Redisson 未配置（getRedissonClient → null）→ 本地语义直跑
    }

    @Test
    void lockContentionSkipsEvaluation() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(manager.getRedissonClient("main")).thenReturn(client);
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);
        job().evaluate();
        verify(t, never()).execute(same(TrLua.EVALUATE_TRANSITION), anyList(), any(Object[].class));
    }

    @Test
    void lockAcquiredRunsAndUnlocks() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(manager.getRedissonClient("main")).thenReturn(client);
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        entriesOf("t1", Set.of("e1"));
        verdictByEntry("e1");
        job().evaluate();
        verify(lock).unlock();
    }

    @Test
    void offlineTableAndMissingIdsAreSkipped() {
        entriesOf("t1", null); // ids == null → continue
        job().evaluate();
        verify(t, never()).execute(same(TrLua.EVALUATE_TRANSITION), anyList(), any(Object[].class));
    }

    @Test
    void unknownVerdictIsIgnoredWithoutSummary() {
        entriesOf("t1", Set.of("e1"));
        when(t.execute(same(TrLua.EVALUATE_TRANSITION), anyList(), any(Object[].class)))
                .thenReturn(List.of("SOMETHING_ELSE"));
        job().evaluate(); // moved+thawed == 0 → 无汇总日志分支
    }

    @Test
    void nullResultIsIgnored() {
        entriesOf("t1", Set.of("e1"));
        when(t.execute(same(TrLua.EVALUATE_TRANSITION), anyList(), any(Object[].class))).thenReturn(null);
        job().evaluate();
    }
}
