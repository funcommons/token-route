# TokenGo 门户只读运维页对接走查（issue #5 R4）

> 对象:TokenGo 平台门户自建的 token-route 只读运维页（条目/状态/租约视图）+ refresh ping 探活。
> 结论先行:**本仓服务本体近零改动**——所需能力全部已交付(v1.0.0 起),本文为对接走查清单。
> 接口字段级契约见 `docs/开发文档/02_接口契约.md` §7(ops 只读面)/ §6(TR-CTR-004)。

## 1. 对接面清单(全部现成)

| 能力 | 接口 | 返回要点 | 鉴权 |
|---|---|---|---|
| 表 + 条目实时状态 | `GET /v1/ops/tables/{tid}/status` | 表元数据(含 `metadata` 版本追溯、affinity/租约参数)+ `entries[]`:status / frozen_until / win_calls / win_failures / consecutive_failures / weight_effective / **conc_active(并发租约占用)** / rate_window_usage(速率桶水位)+ affinity_active_count | ops 面 `tr.auth.ops-mode`(默认 jwt,policy `tr-admin`) |
| 决议日志 | `GET /v1/ops/tables/{tid}/resolve-logs`→`GET /v1/ops/resolve-logs?table_id=&from=&to=&result=&page=&size=` | 每次 resolve 的条目/亲和/原因码/耗时(ring 热窗 7d;全量走结构化日志→Loki) | 同上 |
| 亲和事件史 | `GET /v1/ops/affinity-events?table_id=&type=BIND\|DETACH` | 绑定/解绑事件(含 ADMIN_SET/ADMIN_DELETE/CONSUMER 来源) | 同上 |
| 状态迁移史 | `GET /v1/ops/state-logs?entry_id=&page=&size=` | from/to/原因(FAIL_RATIO/CONSECUTIVE_FAILS/DISABLE/ADMIN_RESET…)/时刻/触发方 | 同上 |
| refresh ping 探活/立即生效 | `POST /v1/refresh/{tid}` | `{pulled, upserted, removed, elapsed_ms}`;拉取失败保旧值(code 0 + pulled=0 + error) | **跟随契约面模式**(`tr.auth.contract-mode`,02 §3.2)——门户服务端调用建议该表所属消费方同模式 |

> 健康探活另一通道:`GET /actuator/health`(含组件 `tokenRouteRedis`,SLI#8 连通性)——
> `/actuator/**` 不在 /v1 鉴权路径内,生产以网络隔离收敛或独立 management 端口(配置手册 §7)。

## 2. 走查步骤(10 分钟)

```bash
BASE=http://token-route:9302
AUTH=(-H "Authorization: Bearer <tr-admin policy token>")   # ops-mode=jwt;apikey 模式换 -H "X-Api-Key: …"

# ① 表状态:条目/冻结窗/并发占用/速率水位 + metadata 版本号
curl -s "$BASE/v1/ops/tables/llm/status" "${AUTH[@]}" | jq '.data | {table_id, metadata, affinity_active_count}'
curl -s "$BASE/v1/ops/tables/llm/status" "${AUTH[@]}" | jq '.data.entries[] | {entry_id, status, frozen_until, conc_active}'

# ② 决议日志:最近窗口按结果过滤(empty 率观测)
curl -s "$BASE/v1/ops/resolve-logs?table_id=llm&result=empty&page=1&size=20" "${AUTH[@]}" | jq '.data'

# ③ 亲和事件 / 状态迁移史
curl -s "$BASE/v1/ops/affinity-events?table_id=llm&type=DETACH" "${AUTH[@]}" | jq '.data[0:5]'
curl -s "$BASE/v1/ops/state-logs?entry_id=e-xxxx" "${AUTH[@]}" | jq '.data'

# ④ refresh ping(探活 + 导出器数据变更立即生效)
curl -s -X POST "$BASE/v1/refresh/llm" -H "X-Api-Key: $TR_API_KEY" | jq '.data'   # 按契约面模式带凭证
```

期望:①~③ 秒回(ring 热窗内存/Redis 读);④ 返回 `pulled≥1` 且 `elapsed_ms` 量级百毫秒内;
上游故障时 ④ `pulled=0` + error 且 ① 数据不变(保旧值语义)。

## 3. 对接护栏

- **只读纪律**:门户页仅消费上表接口;条目数据变更走上游 FEED 真源,运营写操作(切流/状态重置)
  走内部管理面 `/v1/admin/**`(独立内部令牌,不与门户只读混用凭证);
- **ring 热窗 7d**:更早历史走结构化日志(`[TR-RESOLVE]`/`[TR-STATE]`)→ Loki,门户不承诺全量史;
- 轮询频率建议 ≥ 10s 级;SLI 告警阈值参考 `SLI告警建议.md`(门户可复用其指标对照);
- 分页参数上限 `size ≤ 1000`(越界截断)。

## 4. 版本对照

| 能力 | 版本 |
|---|---|
| ops 四只读接口 + 信封 | v1.0.0 |
| 状态迁移史含 ADMIN_RESET(运营重置可见) | v1.0.0 |
| TR-OPS-001 回显 `metadata`(策略版本追溯,TokenGo 门户展示版本号用) | v1.2.0 |
