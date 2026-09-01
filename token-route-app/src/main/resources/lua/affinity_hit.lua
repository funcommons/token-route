-- L2 affinity_hit（03_数据设计 §3）：亲和命中双校验（态域 + 容量）→ TTL 续期 + 预占
-- 任一出域 → MISS/CAPACITY 触发重绑（01 §4.2 惰性脱离语义）
-- KEYS[1]=tr:affinity:{tid}:{session} KEYS[2]=tr:entry:{tid}:{eid} KEYS[3]=tr:conc:{eid}
-- KEYS[4]=tr:rate:{eid}:req KEYS[5]=tr:rate:{eid}:unit
-- ARGV[1]=now_ms ARGV[2]=affinity_ttl_s ARGV[3]=lease_id ARGV[4]=lease_ttl_s
-- ARGV[5]=max_conc('-'=∞) ARGV[6]=req_limit('-'=∞) ARGV[7]=req_window_ms
-- ARGV[8]=unit_limit('-'=∞) ARGV[9]=unit_window_ms

local now = tonumber(ARGV[1])
local maxc = tonumber(ARGV[5])
local reql = tonumber(ARGV[6])
local unitl = tonumber(ARGV[8])

local eid = redis.call('GET', KEYS[1])
if not eid then
    return {'MISS'}
end

-- 态域校验：{ACTIVE, DEGRADED_L1} 参与；FROZEN 且 frozen_until < now 视同解冻（读取时判定）
local raw = redis.call('GET', KEYS[2])
if not raw then
    return {'MISS', eid}
end
local ok, entry = pcall(cjson.decode, raw)
if not ok or type(entry) ~= 'table' then
    return {'MISS', eid}
end
local st = entry.status or 'ACTIVE'
if st ~= 'ACTIVE' and st ~= 'DEGRADED_L1' then
    if st == 'FROZEN' and entry.frozen_until and tonumber(entry.frozen_until) < now then
        st = 'ACTIVE' -- 冻结窗满惰性回（01 §4.2）
    else
        return {'MISS', eid}
    end
end

-- 容量校验（并发 + 双桶，语义同 L1）
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
if maxc and maxc >= 0 and redis.call('ZCARD', KEYS[3]) >= maxc then
    return {'CAPACITY', eid}
end
if reql and reql >= 0 then
    redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now - tonumber(ARGV[7]))
    if redis.call('ZCARD', KEYS[4]) >= reql then
        return {'CAPACITY', eid}
    end
end
if unitl and unitl >= 0 then
    redis.call('ZREMRANGEBYSCORE', KEYS[5], '-inf', now - tonumber(ARGV[9]))
    if redis.call('ZCARD', KEYS[5]) >= unitl then
        return {'CAPACITY', eid}
    end
end

-- TTL 滑动续期 + 并发预占
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
redis.call('ZADD', KEYS[3], now + tonumber(ARGV[4]) * 1000, ARGV[3])

return {'HIT', eid}
