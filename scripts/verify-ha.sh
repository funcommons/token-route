#!/usr/bin/env bash
# 双实例评估器互斥实测（上线检查单 §部署，⑤.2/① 收尾）：
# 两实例（9302/9303）均开 Redisson，观察 tr_evaluator_run_total（持锁成功才 +1）。
# 判定：观察窗内两实例计数之和 ≈ 评估轮数（1 轮/分钟）；若互斥失效（本地锁退化），
# 每轮两家都执行，计数和 ≈ 2×轮数 —— 以此区分 PASS / FAIL。
# 耗时 ~4 分钟（评估器 initialDelay 60s + 两轮观察）。用法：bash scripts/verify-ha.sh
set -euo pipefail
BASE1=http://localhost:9302
BASE2=http://localhost:9303
fail() { echo "HA FAIL: $1"; exit 1; }

docker compose up -d app app2 >/dev/null 2>&1 || true
for base in "$BASE1" "$BASE2"; do
  for i in $(seq 1 40); do
    curl -sf -o /dev/null "$base/v1/ping" 2>/dev/null && break
    [ "$i" = 40 ] && fail "$base 120s 内未就绪"
    sleep 3
  done
done
echo "两实例就绪（9302 / 9303，Redisson 已使能）"

metric() { curl -s "$1/actuator/prometheus" | grep -E "^tr_evaluator_run_total" | awk '{print $2}'; }
A0=$(metric "$BASE1"); B0=$(metric "$BASE2"); A0=${A0:-0}; B0=${B0:-0}
echo "观察 150s（覆盖 ≥2 个评估轮）…"
sleep 150
A1=$(metric "$BASE1"); B1=$(metric "$BASE2"); A1=${A1:-0}; B1=${B1:-0}
DA=$(( ${A1%.*} - ${A0%.*} )); DB=$(( ${B1%.*} - ${B0%.*} ))
TOTAL=$(( DA + DB ))
echo "增量：app(9302)=+$DA  app2(9303)=+$DB  合计=$TOTAL"

# 150s 窗口含 2~3 轮：互斥成立时合计 ≤3 且至少 1 家执行；
# 互斥失效时合计 ≥4（每轮两家都跑）
if [ "$TOTAL" -ge 1 ] && [ "$TOTAL" -le 3 ]; then
  echo "HA PASS: 评估器多实例互斥成立（合计=$TOTAL ≈ 轮数；失效时应为 ≥4）"
else
  fail "合计=$TOTAL——互斥疑似失效（远超轮数）"
fi
