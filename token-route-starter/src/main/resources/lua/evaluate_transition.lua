-- L4 evaluate_transition（03_数据设计 §3）：评估器逐条目（每分钟调度 + Redisson 多实例锁在 Java 侧）
-- 窗清理 → 计数 → 降级爬升（ACTIVE→L1→L2→L3）/ 一步恢复（失败率 <10%）/ 惰性解冻（frozen_until < now）
-- CAS 式迁移（脚本内比对当前 status）；OFFLINE 不动（仅 FEED 管理位）
-- KEYS[1]=tr:entry:{tid}:{eid} KEYS[2]=tr:win:{eid} KEYS[3]=tr:log:state:{eid}
-- ARGV[1]=now_ms ARGV[2]=min_calls(10) ARGV[3]=degrade_ratio(0.5) ARGV[4]=recover_ratio(0.1)

local now = tonumber(ARGV[1])
local min_calls = tonumber(ARGV[2])
local degrade_ratio = tonumber(ARGV[3])
local recover_ratio = tonumber(ARGV[4])

redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - 90000)

local raw = redis.call('GET', KEYS[1])
if not raw then
    return {'SKIP', 'NO_ENTRY'}
end
local ok, entry = pcall(cjson.decode, raw)
if not ok or type(entry) ~= 'table' then
    return {'SKIP', 'BAD_JSON'}
end

local status = entry.status or 'ACTIVE'

-- 惰性解冻：冻结窗满 → ACTIVE（freeze_count 保留供下次退避升档）
if status == 'FROZEN' then
    if entry.frozen_until and tonumber(entry.frozen_until) < now then
        local from = status
        entry.status = 'ACTIVE'
        entry.frozen_until = nil
        redis.call('SET', KEYS[1], cjson.encode(entry))
        redis.call('RPUSH', KEYS[3], cjson.encode({from = from, to = 'ACTIVE', reason = 'FREEZE_WINDOW_PASSED', at = now, by = 'STATE_MACHINE'}))
        redis.call('LTRIM', KEYS[3], -1000, -1)
        redis.call('EXPIRE', KEYS[3], 604800)
        return {'THAWED', 'ACTIVE'}
    end
    return {'SKIP', 'FROZEN'}
end

if status == 'OFFLINE' then
    return {'SKIP', 'OFFLINE'}
end

-- 窗内计数（60s 评估窗）
local members = redis.call('ZRANGEBYSCORE', KEYS[2], now - 60000, '+inf')
local calls, fails = 0, 0
for _, m in ipairs(members) do
    calls = calls + 1
    if string.sub(m, -5) == ':fail' then
        fails = fails + 1
    end
end

local function transition(to, reason)
    local from = status
    entry.status = to
    redis.call('SET', KEYS[1], cjson.encode(entry))
    redis.call('RPUSH', KEYS[3], cjson.encode({from = from, to = to, reason = reason, at = now, by = 'STATE_MACHINE'}))
    redis.call('LTRIM', KEYS[3], -1000, -1)
    redis.call('EXPIRE', KEYS[3], 604800)
    return {'MOVED', to, calls, fails}
end

if calls >= min_calls then
    local fail_ratio = fails / calls
    if fail_ratio >= degrade_ratio then
        -- 爬升：L1→L2→L3；ACTIVE→L1；L3 停留
        if status == 'ACTIVE' then
            return transition('DEGRADED_L1', 'FAIL_RATIO')
        elseif status == 'DEGRADED_L1' then
            return transition('DEGRADED_L2', 'FAIL_RATIO')
        elseif status == 'DEGRADED_L2' then
            return transition('DEGRADED_L3', 'FAIL_RATIO')
        end
        return {'SKIP', 'L3'}
    elseif fail_ratio < recover_ratio and status ~= 'ACTIVE' then
        -- 一步恢复（L1/L2/L3 → ACTIVE）
        return transition('ACTIVE', 'RECOVERED')
    end
end

return {'SKIP', 'NO_CHANGE'}
