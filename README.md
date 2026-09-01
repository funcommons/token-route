# token-route

[![ci](https://github.com/funcommons/token-route/actions/workflows/ci.yml/badge.svg)](https://github.com/funcommons/token-route/actions/workflows/ci.yml)

> 通用 API 路由服务 —— 路由决议 + 条目容量治理（上游限速/限并发），**只做决议与记账，不代理流量、不做用户限流**。

## 这是什么

给多上游（多渠道 / 多实例 / 多供应商）的 API 调用做**有状态路由**的独立微服务：

- **双接口模型** —— `resolve`（取条目 + **并发预占**，返回 `lease_id`）+ `report`（三态回填：成功 / 失败重试 / 失败禁用，驱动集中状态机与速率记账）；无 ver、无 fallbacks——每次调用直连决议，failover = 重新 resolve
- **路由表 / 策略 / 条目** —— 加权随机、顺序轮询、权重优先（weight 允许 float/负数，负数即价格优先变形）、**Groovy 脚本选择器**（集合级：代入条目集返回条目 ID）；条目 `data_json` 不透明（仅 `capacity` 约定字段进内核），任何上游皆可挂
- **条目容量治理** —— 上游限速/限并发进决议过滤：满载/超速条目对新会话出局；并发租约超时惰性回收（默认 30s）；双速率桶（按次 / 按 token / 按字节）
- **会话亲和** —— 同一会话黏住同一条目：空闲超时（默认 8h）/ 绑定条目连败脱离 / 满载脱离 / 亲和解除
- **条目状态机** —— ACTIVE / DEGRADED_L1~L3 / FROZEN / OFFLINE；消费方只回填结果，服务端集中判定（降级权重 ×0.5、连败冻结 5min×4ⁿ 退避）
- **全 FEED 数据面 + 零管理写面** —— 表壳与脚本走 config 种子（启动编译 fail-fast，换表 = 滚动重启）；条目数据经 `refresh_url` 拉取自愈（惰性 60s / ping 立即 / 冷启动回源）；上游只需暴露一个 GET 端点
- **Redis only** —— 无实体表、无 PG、无快照；部署 = app + Redis

## 定位与非目标

| 它是 | 它不是 |
|---|---|
| 路由**决议 + 容量治理**服务：resolve 返回选中条目与预占凭证，换条目由消费方执行 | ❌ 不代理业务调用（与 inference-gateway / API 网关职责正交） |
| 通用路由：条目语义不透明，策略 / 亲和 / 状态机无领域绑定 | ❌ 不做**调用方/用户·key 限流**（网关侧职责；只做条目=上游的限速/并发治理） |
| 族内可复用件：token-gateway 任务面（M2.5）为首要消费方；适用中低 QPS 调度/任务类场景 | ❌ 不适配高 QPS 低延迟热路径（每次直连 +1 跳、无本地缓存——LLM 同步面走 THMP 契约，分层共存） |

## 文档

### 开发文档（docs/开发文档/）

| 文档 | 说明 |
|---|---|
| [开发原则.md](./docs/开发文档/开发原则.md) | **后端开发纪律**：fwk4j 优先不重复建设 / 四方对齐 DoD / copy benefit4j 改造不从 0 开始 |
| [核心业务流程.md](./docs/开发文档/核心业务流程.md) | **图集入口**：总体架构图 / 两大核心流程图 / 状态机 / 关键时序图 |
| [01_设计方案.md](./docs/开发文档/01_设计方案.md) | 设计方案（定位 / 能力边界 / 领域模型 / 关键机制 / API / 分期） |
| [02_接口契约.md](./docs/开发文档/02_接口契约.md) | 两面全接口契约（鉴权三模式 / 错误码 / resolve 预占 / report 三态 / FEED 数据契约） |
| [03_数据设计.md](./docs/开发文档/03_数据设计.md) | Redis 键空间字典（Lua 原子操作 / 租约与双速率桶 / TTL 矩阵 / 容量） |
| [04_流程时序图.md](./docs/开发文档/04_流程时序图.md) | 6 条主干链路时序图（resolve / report / 评估器 / FEED / config 生效链 / 故障自愈） |
| [05_脚本引擎规格.md](./docs/开发文档/05_脚本引擎规格.md) | Groovy 沙箱（选型 ADR / 沙箱三件套 / 上下文 / 两用途语义 / 逃逸向量） |
| [07_实施计划.md](./docs/开发文档/07_实施计划.md) | P1 编码路线（S0~S7 / 出口闸门 / 测试与安全向量 / 里程碑） |
| [08_复用扩展方案.md](./docs/开发文档/08_复用扩展方案.md) | MMagiX/TokenGo 场景覆盖映射（适配判定 / 双平台路线 / 明确不做） |

### 用户文档（docs/用户文档/，仿 GVP 结构：快速开始 → 用户指南 → 运维 → 配置 → 最佳实践 → FAQ）

| 文档 | 说明 |
|---|---|
| [快速开始.md](./docs/用户文档/快速开始.md) | **10 分钟跑通**（模拟 FEED 上游 / 最小表种子 / 三步闭环验证 / 冻结解冻演练） |
| [01_消费方接入手册.md](./docs/用户文档/01_消费方接入手册.md) | 用户指南·自包含（五分钟了解 / 术语表 / lease_id 纪律 / 四接口详解与三态判定指南 / HTTP+SDK 完整走查 / 亲和与 failover / 陷阱速查 / 接入自查清单） |
| [02_管理与运维手册.md](./docs/用户文档/02_管理与运维手册.md) | 运维手册（config 种子建表走查 / FEED 上游契约 / ops 查询 / 告警与排障） |
| [配置手册.md](./docs/用户文档/配置手册.md) | 全量配置项参考（**tr.tables 表定义种子** / tr.* 运行时参数 / env 清单） |
| [最佳实践.md](./docs/用户文档/最佳实践.md) | 稳定性准则（降级预案 / 批量回填 / 亲和准则 / failover 纪律 / capacity 设定 / 反模式清单） |
| [FAQ.md](./docs/用户文档/FAQ.md) | 常见问题 23 问（一般 / 接入 / 运行时 / 运维） |

## 当前状态

**P1 编码完成（S0~S7 全部出口闸门通过）**——进度锚点见 [CODING-PROGRESS.md](./CODING-PROGRESS.md)：

- **S0~S7 全链落地**：骨架/脚本沙箱/resolve 预占/report 三态记账/评估器/FEED 拉取/ops 只读面/OpenAPI 导出/SDK 薄件/全链冒烟 ×2 连跑全绿
- **测试规模**：单测 96 + IT 30（Testcontainers Redis）全绿；冒烟 8 组 ×2
- **待办（P2）**：report 批量 pipeline 优化、ops /metrics 暴露、channel-legacy state_policy 预设、生产 Redisson 锁配置

## 构建与运行

```bash
# 单测（无 Docker 依赖）
mvn test

# 完整门槛构建（单测 + IT + JaCoCo 覆盖率闸门，需 Docker；无 Docker 时加 -Djacoco.skip=true 只跳门槛）
mvn verify -Dtr.it=true

# 本地起服务（app:9302 + redis + mock FEED 上游）
docker compose up -d --build

# 冒烟：S0 三项 / S7 全链 8 组（建议 ×2 连跑）
bash scripts/smoke-s0.sh
bash scripts/smoke.sh 1 && bash scripts/smoke.sh 2

# SDK 消费方坐标
<dependency>
  <groupId>fun.commons</groupId>
  <artifactId>token-route-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**覆盖率闸门**（07_实施计划 §覆盖率）：总行 ≥80% / 分支 ≥70%；TrCode 与脚本引擎核心类 ≥90%。
当前实测（`mvn verify -Dtr.it=true`，单测+IT 合并口径）：**行 92.8% / 分支 82.9%**；
TrCode、TrScriptFunctions、TrMethodBlacklistCustomizer 100%，TrScriptEngine/TrScriptLoader 92%。

> 网络受限环境：Docker 镜像源与 jitpack 说明见 `CODING-PROGRESS.md` 环境节；Dockerfile 基础镜像走 ARG 可覆盖。

## funcommons 仓族

| 项目 | 关系 |
|---|---|
| [token-gateway](https://github.com/funcommons/token-gateway) | 首要消费方：LLM 面走 THMP 契约，任务面（M2.5）走 token-route |
| thmp-app | 供给候选解析（分层共存）；远期健康状态机可注册为 token-route feed |
| [token-mock](https://github.com/funcommons/token-mock) | 集成测试假上游——与路由条目天然互补（故障注入驱动态状态机与容量治理演练） |
