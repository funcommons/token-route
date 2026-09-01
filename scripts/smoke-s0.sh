#!/usr/bin/env bash
# S0 compose 冒烟（07_实施计划 S0 出口闸门）：
#   ① 契约面 /v1/ping → HTTP 200 + code 0 + 6 字段信封（none 直过）
#   ② ops 面 /v1/ops/ping（默认 jwt）→ HTTP 200 + code 10200
# 用法: bash scripts/smoke-s0.sh   （前置: docker compose up -d --build）
set -euo pipefail

BASE="${BASE:-http://localhost:9302}"
fail() { echo "SMOKE FAIL: $1"; exit 1; }

# 等待应用就绪（最多 120s）
for i in $(seq 1 60); do
  if curl -sf -o /dev/null "$BASE/v1/ping" 2>/dev/null; then break; fi
  sleep 2
  [ "$i" = 60 ] && fail "应用 120s 内未就绪"
done

# ①+③ 契约面 ping：none 直过，code 0，信封字段齐全（fwk4j NON_NULL：error 为空时省略）
BODY=$(curl -s "$BASE/v1/ping")
echo "$BODY" | grep -q '"code":0' || fail "契约面 ping code != 0: $BODY"
for k in code message data trace_id timestamp; do
  echo "$BODY" | grep -q "\"$k\"" || fail "信封缺字段 $k: $BODY"
done
echo "①/③ 契约面 ping（none 直过 + 信封）✓"

# ② ops 面 ping：默认 jwt 模式，无 token → HTTP 200 + code 10200
CODE=$(curl -s -o /tmp/ops.json -w '%{http_code}' "$BASE/v1/ops/ping")
[ "$CODE" = "200" ] || fail "ops 面 ping HTTP 码非 200: $CODE"
grep -q '"code":10200' /tmp/ops.json || fail "ops 面 ping code != 10200: $(cat /tmp/ops.json)"
echo "② ops 面 ping（jwt 无 token → 200 + 10200）✓"

echo "SMOKE PASS: S0 三项冒烟全绿"
