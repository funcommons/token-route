# SLI 告警规则建议（07_实施计划 S7）

> 基于 01_设计方案 §9 非功能性保障的可观测指标。ring 仅热窗，全量指标以结构化日志（→ Loki）与计数器为准。
> 级别：P0 电话 / P1 IM 即时 / P2 日报。阈值基于 P1 规模假设（10 表 × 200 条目，QPS 中低），上线后按基线校准。

| # | SLI | 来源 | 告警阈值 | 级别 | 处置 |
|---|---|---|---|---|---|
| 1 | resolve EMPTY 率（reasons 非空占比） | `[TR-RESOLVE]` reasons | 5m 窗 > 30% | P0 | 上游集体故障/冻结——查 ops 表状态 + FEED 上游健康 |
| 2 | resolve P99 | 结构化日志 elapsed_ms | 5m 窗 > 20ms（01 §9） | P2 | 观察 Redis 延迟；热路径全 Lua 单往返，超预算多因 Redis 抖动 |
| 3 | report P99 | 同上 | 5m 窗 > 30ms | P2 | 批量 ≤100 逐条 L3；持续超预算改 pipeline（P2 优化项） |
| 4 | FEED 刷新失败率 | `[TR-FEED]` warn/error 日志 | 连续失败 ≥3（单表） | P1 | 上游 refresh_url 不可达/坏 schema（10633）；失败保旧值，确认上游修复后 refresh ping |
| 5 | script_degraded_total 增速 | 服务端计数器（05 §7） | 5m 窗增量 > 0 且持续 | P1 | filter/selector 运行期异常——定位表与脚本；编译期问题不会进线上（fail-fast） |
| 6 | 租约超时回收数 | L6 回收计数（ops conc_active 对照） | 5m 窗 > 500 或环比陡增 | P1 | 消费方未回填 report（lease 丢失）；速率照计保守向，提醒消费方检查透传纪律 |
| 7 | 状态迁移数突增 | `[TR-STATE]` / log:state ring | 5m 窗 > 基线 10 倍 | P1 | 上游批量故障（误冻结排查：01 §11#6 阈值保守+惰性解冻兜底） |
| 8 | Redis 连通性 | `MultiRedisManager.checkHealth` | 任一探测失败 | P0 | resolve EMPTY / report 10700 已按降级语义工作；恢复后 FEED 回源自愈 |
| 9 | app 实例存活 | /v1/ping 探活 | 任一实例 down | P1 | 双实例无状态；单实例存活即 SLA 内 |

## 上报通道

- 结构化日志：`[TR-RESOLVE]` / `[TR-STATE]` / `[TR-SCRIPT]` / `[TR-FEED]` 单行 → 文件（P1 过渡）→ Loki（M4 起，01 §9）。
- ring 仅 ops 查询热窗（`/v1/ops/*`），不作告警数据源。
- 计数器（script_degraded_total / lease_reclaim）当前为进程内存，暴露 `/metrics` 属 P2 增强（随 ops 可视化页面）。
