-- L6 lease_reclaim（03_数据设计 §3）：租约超时惰性回收
-- 回收时速率照计（保守向）+ 健康窗不补计（不写 win/fail）；顺带滚动双速率桶窗口
-- resolve/report 路径顺带触发（无独立定时器）
-- KEYS[1]=tr:conc:{eid} KEYS[2]=tr:rate:{eid}:req KEYS[3]=tr:rate:{eid}:unit KEYS[4]=tr:seq:{eid}
-- ARGV[1]=now_ms ARGV[2]=req_window_ms ARGV[3]=unit_window_ms ARGV[4]=unit_mode(REQUEST→unit 桶不写)

local now = tonumber(ARGV[1])
local mode = ARGV[4]

-- 窗口滚动
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - tonumber(ARGV[2]))
if mode ~= 'REQUEST' then
    redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - tonumber(ARGV[3]))
end

-- 回收过期租约
local reclaimed = tonumber(redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now))

-- 速率照计（保守最小 1 次 / 1 单位）；健康窗不补计
if reclaimed > 0 then
    for _ = 1, reclaimed do
        local seq = redis.call('INCR', KEYS[4])
        redis.call('ZADD', KEYS[2], now, seq .. ':1')
        if mode ~= 'REQUEST' then
            redis.call('ZADD', KEYS[3], now, seq .. ':1')
        end
    end
end

return reclaimed
