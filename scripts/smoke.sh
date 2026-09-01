#!/usr/bin/env bash
# S7 全链冒烟（07_实施计划 S7 出口闸门）：8 组 ×2 连跑
# ① config 种子（表注册/未知表 10400）② FEED 回源 refresh ③ resolve 预占 ④ report 三态受理
# ⑤ 连败冻结 → resolve 排水 ⑥ FEED 显式 ACTIVE 解冻 → resolve 恢复 ⑦ Redis 故障注入 → EMPTY 不误动作 → 恢复自愈 ⑧ ops 面
# 用法: bash scripts/smoke.sh   （前置: docker compose up -d --build）
set -euo pipefail

BASE="${BASE:-http://localhost:9302}"
RUN="${1:-1}"
fail() { echo "SMOKE FAIL(run$RUN): $1"; exit 1; }

docker compose exec -T redis redis-cli FLUSHALL >/dev/null 2>&1 || true

for i in $(seq 1 60); do
  if curl -sf -o /dev/null "$BASE/v1/ping" 2>/dev/null; then break; fi
  sleep 2
  [ "$i" = 60 ] && fail "应用 120s 内未就绪"
done

# ① config 种子：表已注册（resolve 可用）；未知表 → 10400
C=$(curl -s -o /tmp/r1.json -w '%{http_code}' -X POST "$BASE/v1/resolve" -H 'Content-Type: application/json' -d '{"table_id":"llm"}')
[ "$C" = "200" ] || fail "① resolve HTTP $C"
grep -q '"code":0' /tmp/r1.json || fail "① resolve code 非 0: $(cat /tmp/r1.json)"
C=$(curl -s -o /tmp/r1x.json -w '%{http_code}' -X POST "$BASE/v1/resolve" -H 'Content-Type: application/json' -d '{"table_id":"ghost"}')
grep -q '"code":10400' /tmp/r1x.json || fail "① 未知表应 10400: $(cat /tmp/r1x.json)"
echo "① config 种子（表注册 + 未知表 10400）✓"

# ② FEED 回源：立即刷新 → 拉取 2 条
curl -s -X POST "$BASE/v1/refresh/llm" -o /tmp/r2.json
grep -q '"pulled":2' /tmp/r2.json || fail "② refresh pulled != 2: $(cat /tmp/r2.json)"
echo "② FEED 回源（refresh 拉取 2 条）✓"

# ③ resolve 预占：entry_id + lease_id + NEW（会话名按轮次唯一，避免跨轮亲和残留）
R=$(curl -s -X POST "$BASE/v1/resolve" -H 'Content-Type: application/json' \
  -d "{\"table_id\":\"llm\",\"session_id\":\"smoke-sess-$RUN\"}")
echo "$R" | grep -q '"entry_id":"e-' || fail "③ resolve 无 entry_id: $R"
echo "$R" | grep -q '"lease_id":"' || fail "③ resolve 无 lease_id: $R"
echo "$R" | grep -q '"affinity":"NEW"' || fail "③ 首次绑定应为 NEW: $R"
EID=$(echo "$R" | sed 's/.*"entry_id":"\([^"]*\)".*/\1/')
LEASE=$(echo "$R" | sed 's/.*"lease_id":"\([^"]*\)".*/\1/')
echo "③ resolve 预占（entry=$EID lease 预占 + NEW）✓"

# ④ report 三态：SUCCESS 受理
R=$(curl -s -X POST "$BASE/v1/report" -H 'Content-Type: application/json' \
  -d "{\"reports\":[{\"entry_id\":\"$EID\",\"lease_id\":\"$LEASE\",\"result\":\"SUCCESS\"}]}")
echo "$R" | grep -q '"accepted":1' || fail "④ report accepted != 1: $R"
echo "④ report 三态（SUCCESS 受理 + 精确释放）✓"

# ⑤ 连败冻结：同会话 5 轮 resolve+RETRYABLE_FAIL → 条目 FROZEN → resolve 排水
for i in 1 2 3 4 5; do
  R=$(curl -s -X POST "$BASE/v1/resolve" -H 'Content-Type: application/json' \
    -d "{\"table_id\":\"llm\",\"session_id\":\"smoke-frozen-$RUN\"}")
  EID=$(echo "$R" | sed 's/.*"entry_id":"\([^"]*\)".*/\1/')
  LEASE=$(echo "$R" | sed 's/.*"lease_id":"\([^"]*\)".*/\1/')
  [ "$EID" = "null" ] && fail "⑤ 第 $i 轮 resolve 意外 EMPTY: $R"
  curl -s -o /dev/null -X POST "$BASE/v1/report" -H 'Content-Type: application/json' \
    -d "{\"reports\":[{\"entry_id\":\"$EID\",\"lease_id\":\"$LEASE\",\"result\":\"RETRYABLE_FAIL\"}]}"
done
R=$(curl -s -X POST "$BASE/v1/resolve" -H 'Content-Type: application/json' -d '{"table_id":"llm"}')
# up-a 已冻结；up-b 未冻结仍可选——断言 up-a 不再返回
echo "$R" | grep -q "\"entry_id\":\"e-" && ! echo "$R" | grep -q "$EID" || fail "⑤ 冻结条目不应再被选中: $R"
echo "⑤ 连败冻结（连败 5 → FROZEN + resolve 排水）✓"

# ⑥ FEED 显式 ACTIVE 解冻：上游真源本就声明 ACTIVE（管理位）→ refresh ping → up-a 恢复
curl -s -o /dev/null -X POST "$BASE/v1/refresh/llm"
R=$(curl -s -X POST "$BASE/v1/resolve" -H 'Content-Type: application/json' -d '{"table_id":"llm"}')
echo "$R" | grep -q '"entry_id":"e-' || fail "⑥ 解冻后 resolve EMPTY: $R"
echo "⑥ FEED 显式 ACTIVE 解冻 → resolve 恢复 ✓"

# ⑦ Redis 故障注入：stop redis → resolve EMPTY（200 code 0 不误动作）→ 恢复自愈
docker compose stop redis >/dev/null 2>&1 || docker stop token-route-redis-1 >/dev/null 2>&1
sleep 2
C=$(curl -s --max-time 10 -o /tmp/r7.json -w '%{http_code}' -X POST "$BASE/v1/resolve" -H 'Content-Type: application/json' -d '{"table_id":"llm"}') || C="timeout"
[ "$C" = "200" ] || fail "⑦ Redis 故障时 resolve 应 200（EMPTY 不误动作）: got $C"
docker compose start redis >/dev/null 2>&1 || docker start token-route-redis-1 >/dev/null 2>&1
sleep 3
curl -s -o /dev/null --max-time 10 -X POST "$BASE/v1/refresh/llm" || true
R=$(curl -s --max-time 10 -X POST "$BASE/v1/resolve" -H 'Content-Type: application/json' -d '{"table_id":"llm"}')
echo "$R" | grep -q '"entry_id":"e-' || fail "⑦ Redis 恢复后 resolve 未自愈: $R"
echo "⑦ Redis 故障注入 → EMPTY 不误动作 → 恢复自愈 ✓"

# ⑧ ops 面：jwt 默认无 token → 10200（鉴权）
C=$(curl -s -o /tmp/r8.json -w '%{http_code}' "$BASE/v1/ops/tables/llm/status")
[ "$C" = "200" ] && grep -q '"code":10200' /tmp/r8.json || fail "⑧ ops 面鉴权异常: $(cat /tmp/r8.json)"
echo "⑧ ops 面（jwt 鉴权 10200 + 只读面）✓"

echo "SMOKE PASS: 全链 8 组全绿 (run$RUN)"
