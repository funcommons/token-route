-- L5 entry_upsert（03_数据设计 §3）：FEED 全量对比，确定性 eid 直接寻址（无需 name 索引）
-- 集内 upsert（status 缺省保留现值/显式 ACTIVE/OFFLINE 为管理位）+ 差集移除（亲和绑定惰性脱离，无反向索引不扫描）
-- KEYS[1]=tr:entry-ids:{tid}
-- ARGV[1]=tid ARGV[2]=key 前缀(tr) ARGV[3..]=成对 (eid, feedJson)
-- 返回 {added, updated, removed}

local tid = ARGV[1]
local prefix = ARGV[2]
local added, updated = 0, 0

for i = 3, #ARGV, 2 do
    local eid = ARGV[i]
    local feedJson = ARGV[i + 1]
    local entryKey = prefix .. ':entry:' .. tid .. ':' .. eid
    local existing = redis.call('GET', entryKey)
    if not existing then
        redis.call('SADD', KEYS[1], eid)
        redis.call('SET', entryKey, feedJson)
        added = added + 1
    else
        -- status 缺省 = 保留现值（不重置状态机判定态）；显式 ACTIVE/OFFLINE 为管理位（02 §8）
        local okNew, feed = pcall(cjson.decode, feedJson)
        local okOld, cur = pcall(cjson.decode, existing)
        if okNew and type(feed) == 'table' and okOld and type(cur) == 'table' then
            if feed.status == nil or feed.status == '' then
                feed.status = cur.status or 'ACTIVE'
            end
            -- 管理位只改 status；冻结窗/冻结次数等运行时字段保留
            if feed.status == 'ACTIVE' and cur.status == 'FROZEN' then
                -- 显式 ACTIVE = 管理位解冻：清冻结窗
                feed.frozen_until = nil
                feed.freeze_count = cur.freeze_count
            else
                feed.status = feed.status or cur.status
                feed.frozen_until = cur.frozen_until
                feed.freeze_count = cur.freeze_count
            end
            redis.call('SET', entryKey, cjson.encode(feed))
        else
            redis.call('SET', entryKey, feedJson)
        end
        redis.call('SADD', KEYS[1], eid)
        updated = updated + 1
    end
end

-- 全量对比：本次返回集缺失的条目移除（其亲和绑定惰性脱离——L2 校验 entry 缺失自然 MISS 重绑）
local removed = 0
local current = redis.call('SMEMBERS', KEYS[1])
local present = {}
for i = 3, #ARGV, 2 do
    present[ARGV[i]] = true
end
for _, eid in ipairs(current) do
    if not present[eid] then
        redis.call('SREM', KEYS[1], eid)
        redis.call('DEL', prefix .. ':entry:' .. tid .. ':' .. eid)
        removed = removed + 1
    end
end

return {added, updated, removed}
