-- L1 resolve_bind（03_数据设计 §3）：亲和 MISS 分支原子落账
-- SET NX 绑定 + 容量校验（并发/双桶）+ 并发预占 + 亲和事件 log
-- 同 session 并发 resolve 仅一个 NEW——败者采用胜者绑定（等价 HIT 返回）
-- KEYS[1]=tr:affinity:{tid}:{session} KEYS[2]=tr:conc:{eid} KEYS[3]=tr:rate:{eid}:req
-- KEYS[4]=tr:rate:{eid}:unit KEYS[5]=tr:seq:{eid} KEYS[6]=tr:log:aff:{tid}
-- ARGV[1]=session ARGV[2]=entry_id ARGV[3]=affinity_ttl_s ARGV[4]=lease_id ARGV[5]=lease_ttl_s
-- ARGV[6]=now_ms ARGV[7]=max_conc('-'=∞) ARGV[8]=req_limit('-'=∞) ARGV[9]=req_window_ms
-- ARGV[10]=unit_limit('-'=∞) ARGV[11]=unit_window_ms ARGV[12]=aff_event_json ARGV[13]=log_ttl_s
-- ARGV[14]=bind(1=绑定亲和 0=仅预占——表未开亲和/无 session)

local now = tonumber(ARGV[6])
local maxc = tonumber(ARGV[7])
local reql = tonumber(ARGV[8])
local unitl = tonumber(ARGV[10])
local bind = ARGV[14] == '1'

local function capacity_ok()
    -- 并发租约：先惰性回收过期，再比对上限（resolve 路径顺带 L6 语义）
    redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
    if maxc and maxc >= 0 and redis.call('ZCARD', KEYS[2]) >= maxc then
        return false
    end
    -- 请求桶按次预检
    if reql and reql >= 0 then
        redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - tonumber(ARGV[9]))
        if redis.call('ZCARD', KEYS[3]) >= reql then
            return false
        end
    end
    -- 单位桶按窗内和预检（TOKEN/BIT；REQUEST 口径时该键不写、limit 传 '-'）
    if unitl and unitl >= 0 then
        redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now - tonumber(ARGV[11]))
        if redis.call('ZCARD', KEYS[4]) >= unitl then
            return false
        end
    end
    return true
end

-- 胜者已存在：败者直接采用（不预占新 lease）；仅预占模式跳过亲和
local winner = redis.call('GET', KEYS[1])
if bind and winner then
    return {'EXISTED', winner}
end

if not capacity_ok() then
    return {'CAPACITY'}
end

-- 并发预占
local deadline = now + tonumber(ARGV[5]) * 1000
redis.call('ZADD', KEYS[2], deadline, ARGV[4])

-- 绑定 SET NX（单 NEW 的唯一判定；竞态败者回滚 lease 并采用胜者）
if bind then
    local ok = redis.call('SET', KEYS[1], ARGV[2], 'NX', 'EX', tonumber(ARGV[3]))
    if not ok then
        redis.call('ZREM', KEYS[2], ARGV[4])
        winner = redis.call('GET', KEYS[1])
        if not winner then
            return {'CAPACITY'}
        end
        return {'EXISTED', winner}
    end

    -- 亲和事件 log（LTRIM 1000 + TTL，03 §4）
    redis.call('RPUSH', KEYS[6], ARGV[12])
    redis.call('LTRIM', KEYS[6], -1000, -1)
    redis.call('EXPIRE', KEYS[6], tonumber(ARGV[13]))
end

return {'NEW', ARGV[2]}
