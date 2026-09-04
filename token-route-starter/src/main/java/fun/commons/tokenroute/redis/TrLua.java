package fun.commons.tokenroute.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lua 原子操作清单（03_数据设计 §3）：L1/L2/L6（S2）；L3/L4/L5 随 S3/S4 增加。
 * 运行时热路径全部 Lua 单往返，禁应用层多命令拼装（03 §6）。
 */
public final class TrLua {

    /** L1 resolve_bind → {'NEW'|'EXISTED'|'CAPACITY', entry_id} */
    public static final RedisScript<List> RESOLVE_BIND = load("/lua/resolve_bind.lua");

    /** L2 affinity_hit → {'HIT'|'MISS'|'CAPACITY', entry_id?} */
    public static final RedisScript<List> AFFINITY_HIT = load("/lua/affinity_hit.lua");

    /** L6 lease_reclaim → 回收数量 */
    public static final RedisScript<Long> LEASE_RECLAIM = loadLong("/lua/lease_reclaim.lua");

    /** L3 report_aggregate → {'OK', 状态, 连败数?} | {'REJECTED','LEASE_INVALID'} */
    public static final RedisScript<List> REPORT_AGGREGATE = load("/lua/report_aggregate.lua");

    /** L4 evaluate_transition → {'MOVED'|'THAWED'|'SKIP', ...} */
    public static final RedisScript<List> EVALUATE_TRANSITION = load("/lua/evaluate_transition.lua");

    /** L5 entry_upsert → {added, updated, removed} */
    public static final RedisScript<List> ENTRY_UPSERT = load("/lua/entry_upsert.lua");

    /** L7 state_reset → {'RESET', from} | {'NOOP','ACTIVE'} | {'SKIP', NO_ENTRY|OFFLINE|BAD_JSON} */
    public static final RedisScript<List> STATE_RESET = load("/lua/state_reset.lua");

    private TrLua() {
    }

    @SuppressWarnings("unchecked")
    private static <T> RedisScript<List> load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            DefaultRedisScript<List> script = new DefaultRedisScript<>();
            script.setScriptText(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            script.setResultType(List.class);
            return script;
        } catch (IOException e) {
            throw new IllegalStateException("Lua 脚本加载失败: " + path, e);
        }
    }

    private static RedisScript<Long> loadLong(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptText(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            script.setResultType(Long.class);
            return script;
        } catch (IOException e) {
            throw new IllegalStateException("Lua 脚本加载失败: " + path, e);
        }
    }
}
