-- L3 report_aggregate（03_数据设计 §3）：report 核心，多键原子单往返
-- ZREM 精确释放（迟到/未知 lease 拒收）→ 三态全记账（双桶 ZADD 窗口累加，rate_units=0 = 调用前拒绝）
-- → 三态分支：SUCCESS 清连败；RETRYABLE_FAIL 连败 +1（≥阈值即判 FROZEN 退避）；DISABLE_FAIL 直写 FROZEN + 本会话即时脱离
-- KEYS[1]=tr:conc:{eid} KEYS[2]=tr:rate:{eid}:req KEYS[3]=tr:rate:{eid}:unit KEYS[4]=tr:seq:{eid}
-- KEYS[5]=tr:win:{eid} KEYS[6]=tr:fail:{eid} KEYS[7]=tr:entry:{tid}:{eid} KEYS[8]=tr:log:state:{eid}
-- KEYS[9]=tr:affinity:{tid}:{session}
-- ARGV[1]=lease_id ARGV[2]=result ARGV[3]=rate_units ARGV[4]=now_ms ARGV[5]=req_window_ms
-- ARGV[6]=unit_window_ms ARGV[7]=unit_mode ARGV[8]=freeze_base_ms ARGV[9]=freeze_cap_ms
-- ARGV[10]=fail_threshold ARGV[11]=session(''=不脱离) ARGV[12]=disable(bool 字符串)

local now = tonumber(ARGV[4])

-- 精确释放：lease 不存在（已超时回收/未知）→ 拒收，速率已由回收照计，不重复记账
local removed = redis.call('ZREM', KEYS[1], ARGV[1])
if removed == 0 then
    return {'REJECTED', 'LEASE_INVALID'}
end

-- 窗口滚动（win 90s / 速率桶按各自窗口）
redis.call('ZREMRANGEBYSCORE', KEYS[5], '-inf', now - 90000)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - tonumber(ARGV[5]))
local mode = ARGV[7]
if mode ~= 'REQUEST' then
    redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - tonumber(ARGV[6]))
end

-- 速率记账：请求桶恒 +1 次；单位桶按口径累加 rate_units（0 = 调用前拒绝也留痕）
local units = tonumber(ARGV[3]) or 1
local s1 = redis.call('INCR', KEYS[4])
redis.call('ZADD', KEYS[2], now, s1 .. ':1')
if mode ~= 'REQUEST' then
    local s2 = redis.call('INCR', KEYS[4])
    redis.call('ZADD', KEYS[3], now, s2 .. ':' .. units)
end

local function push_state_log(from, to, reason, by)
    redis.call('RPUSH', KEYS[8], cjson.encode({from = from, to = to, reason = reason, at = now, by = by}))
    redis.call('LTRIM', KEYS[8], -1000, -1)
    redis.call('EXPIRE', KEYS[8], 604800)
end

local function freeze(entry, reason, by)
    local from = entry.status or 'ACTIVE'
    local fc = tonumber(entry.freeze_count) or 0
    local delay = tonumber(ARGV[8]) * math.pow(4, fc)
    if delay > tonumber(ARGV[9]) then
        delay = tonumber(ARGV[9])
    end
    entry.status = 'FROZEN'
    entry.frozen_until = now + delay
    entry.freeze_count = fc + 1
    redis.call('SET', KEYS[7], cjson.encode(entry))
    push_state_log(from, 'FROZEN', reason, by)
end

local result = ARGV[2]
local s3 = redis.call('INCR', KEYS[4])

if result == 'SUCCESS' then
    redis.call('DEL', KEYS[6])
    redis.call('ZADD', KEYS[5], now, s3 .. ':ok')
    return {'OK', 'SUCCESS'}

elseif result == 'RETRYABLE_FAIL' then
    redis.call('ZADD', KEYS[5], now, s3 .. ':fail')
    local fails = tonumber(redis.call('INCR', KEYS[6]))
    if fails >= tonumber(ARGV[10]) then
        -- 连败 ≥ 阈值即判即升 FROZEN（01 §5.3）；FAIL 信号源=STATE_MACHINE
        local raw = redis.call('GET', KEYS[7])
        if raw then
            local ok, entry = pcall(cjson.decode, raw)
            if ok and type(entry) == 'table' then
                freeze(entry, 'CONSECUTIVE_FAILS', 'STATE_MACHINE')
            end
        end
        return {'OK', 'FROZEN', fails}
    end
    return {'OK', 'RETRYABLE_FAIL', fails}

else
    -- DISABLE_FAIL：立即 FROZEN（管理动作，收到即执行）+ 本会话即时脱离（其余惰性）
    redis.call('ZADD', KEYS[5], now, s3 .. ':fail')
    local raw = redis.call('GET', KEYS[7])
    if raw then
        local ok, entry = pcall(cjson.decode, raw)
        if ok and type(entry) == 'table' then
            freeze(entry, 'DISABLE', 'REPORT_DISABLE')
        end
    end
    if ARGV[11] ~= '' and ARGV[12] == '1' then
        redis.call('DEL', KEYS[9])
    end
    return {'OK', 'FROZEN'}
end
