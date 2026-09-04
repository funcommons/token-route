-- L7 state_reset（03_数据设计 §3；TR-ADM-005 运营手动即时复位）
-- 场景：余额不足停用类故障（消费方 DISABLE_FAIL/连败 → FROZEN），充值完成后运营手动立即恢复，
--       不等冻结退避窗（5min×4ⁿ 封顶 2h）/评估窗。
-- 原子复位：status→ACTIVE + frozen_until/freeze_count 清零（退避从头计）+ 连败计数清 + 1m 窗清
-- + 迁移史（by=ADMIN）；OFFLINE 不动（FEED 管理位，须上游显式 ACTIVE）；OFFLINE/ACTIVE 语义同 L4。
-- KEYS[1]=tr:entry:{tid}:{eid} KEYS[2]=tr:fail:{eid} KEYS[3]=tr:win:{eid} KEYS[4]=tr:log:state:{eid}
-- ARGV[1]=now_ms

local raw = redis.call('GET', KEYS[1])
if not raw then
    return {'SKIP', 'NO_ENTRY'}
end
local ok, entry = pcall(cjson.decode, raw)
if not ok or type(entry) ~= 'table' then
    return {'SKIP', 'BAD_JSON'}
end

local from = entry.status or 'ACTIVE'
if from == 'OFFLINE' then
    -- OFFLINE 是 FEED 管理位（上游显式下线），不经运营重置
    return {'SKIP', 'OFFLINE'}
end
if from == 'ACTIVE' and redis.call('EXISTS', KEYS[2]) == 0 then
    return {'NOOP', 'ACTIVE'}
end

entry.status = 'ACTIVE'
entry.frozen_until = nil
entry.freeze_count = nil
redis.call('SET', KEYS[1], cjson.encode(entry))
redis.call('DEL', KEYS[2]) -- 连败计数清零（避免残留计数下一次失败即判冻结）
redis.call('DEL', KEYS[3]) -- 1m 窗清零（恢复判定从零开始，不带旧失败率）
redis.call('RPUSH', KEYS[4], cjson.encode({from = from, to = 'ACTIVE', reason = 'ADMIN_RESET', at = tonumber(ARGV[1]), by = 'ADMIN'}))
redis.call('LTRIM', KEYS[4], -1000, -1)
redis.call('EXPIRE', KEYS[4], 604800)
return {'RESET', from}
