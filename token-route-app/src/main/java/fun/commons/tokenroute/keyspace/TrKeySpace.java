package fun.commons.tokenroute.keyspace;

import org.springframework.util.Assert;

/**
 * Redis 键工厂——键名字典见 03_数据设计 §2（前缀 tr 可配：tr.key-prefix）。
 * 纯字符串拼装，无 Redis 依赖；空键段直接拒绝（防键碰撞）。
 */
public class TrKeySpace {

    private final String prefix;

    public TrKeySpace(String prefix) {
        Assert.hasText(prefix, "key-prefix 不能为空");
        this.prefix = prefix;
    }

    /** 键前缀（L5 Lua 侧拼 entry 键用） */
    public String prefix() {
        return prefix;
    }

    private String key(String... segments) {
        StringBuilder sb = new StringBuilder(prefix);
        for (String s : segments) {
            Assert.hasText(s, "键段不能为空");
            sb.append(':').append(s);
        }
        return sb.toString();
    }

    // —— 条目数据面（真源 = 上游 FEED，Redis 为回源缓存）——

    /** tr:entry-ids:{tid} SET 表下条目 ID 集；键不存在 = 冷启动信号 */
    public String entryIds(String tableId) {
        return key("entry-ids", tableId);
    }

    /** tr:entry:{tid}:{eid} STRING EntryJSON */
    public String entry(String tableId, String entryId) {
        return key("entry", tableId, entryId);
    }

    /** tr:feed:{tid} HASH 拉取游标 last_pull / fail_count */
    public String feed(String tableId) {
        return key("feed", tableId);
    }

    // —— 容量记账面 ——

    /** tr:conc:{eid} ZSET 并发租约 member=lease_id score=deadline */
    public String conc(String entryId) {
        return key("conc", entryId);
    }

    /** tr:rate:{eid}:req ZSET 请求桶 */
    public String rateReq(String entryId) {
        return key("rate", entryId, "req");
    }

    /** tr:rate:{eid}:unit ZSET 单位桶（TOKEN/BIT）；REQUEST 口径不写 */
    public String rateUnit(String entryId) {
        return key("rate", entryId, "unit");
    }

    /** tr:seq:{eid} STRING 桶成员唯一序号 INCR */
    public String seq(String entryId) {
        return key("seq", entryId);
    }

    // —— 运行时状态面 ——

    /** tr:affinity:{tid}:{session} STRING → entry_id（TTL=空闲超时，滑动续期） */
    public String affinity(String tableId, String sessionId) {
        return key("affinity", tableId, sessionId);
    }

    /** tr:win:{eid} ZSET 1m 滑动窗调用观测 */
    public String win(String entryId) {
        return key("win", entryId);
    }

    /** tr:fail:{eid} STRING 连败计数（SUCCESS 清零） */
    public String fail(String entryId) {
        return key("fail", entryId);
    }

    /** tr:rr:{tid} STRING 顺序轮询游标（INCR 取模） */
    public String rr(String tableId) {
        return key("rr", tableId);
    }

    /** tr:log:resolve:{tid} LIST 决议日志 ring（LPTRIM 1000 + TTL 7d） */
    public String logResolve(String tableId) {
        return key("log:resolve", tableId);
    }

    /** tr:log:aff:{tid} LIST 亲和事件 */
    public String logAff(String tableId) {
        return key("log:aff", tableId);
    }

    /** tr:log:state:{eid} LIST 状态迁移史 */
    public String logState(String entryId) {
        return key("log:state", entryId);
    }

    /** tr:sched:lock:v1:{job} 评估器/拉取单飞多实例锁（Redisson tryLock waitTime=0） */
    public String schedLock(String job) {
        return key("sched:lock:v1", job);
    }
}
