# token-gateway 接入指引（token-route 消费方）

> 给 token-gateway 团队的一页纸：怎么把「选上游 + 上游还能不能接活」交给 token-route。
> 完整契约见 `01_消费方接入手册.md`（自包含），本文只讲 gateway 视角的最小闭环与红线。
> 可运行示例见 `examples/gateway-demo/`。

## 1. gateway 该把什么交给 token-route

gateway 的既有职责（协议转换、鉴权、限流用户侧）**不变**。交给 token-route 的只有一件事：
**每次要打上游前问一句"这次用谁"**（resolve），**用完回一句"结果如何"**（report）。

| gateway 场景 | 对应能力 |
|---|---|
| 同一任务的多次请求要打到同一上游（连接/会话复用） | resolve 带 `session_id`（= 任务 ID），亲和自动黏住 |
| 上游不能被打爆（并发/速率） | 条目 `capacity` 进决议过滤，满载对新会话自动出局 |
| 某渠道持续报错要自动降级/冻结 | report 三态回填，服务端集中状态机 |
| 上游列表/权重/容量调整 | FEED 上游 + refresh ping，gateway 零发布 |

## 2. 三步接入

**① 依赖**（jitpack）：

```xml
<repositories><repository><id>jitpack</id><url>https://jitpack.io</url></repository></repositories>

<dependency>
  <groupId>fun.commons</groupId>
  <artifactId>token-route-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**② 客户端**（鉴权三选一：内网 `open` / `apiKey(url, key)` / `jwt(url, tokenSupplier)`）：

```java
TrRouteClient client = TrRouteClient.open("http://token-route:9302");
```

**③ 调用循环**（完整可跑：`examples/gateway-demo`）：

```java
// 任务启动（每轮轮询都用同一个 sessionId）
TrRouteClient.ResolveResult r = client.resolve("llm", taskId, Map.of());
if (r.entryId() == null) { /* EMPTY：按 r.reasons() 处理，勿重试风暴 */ }
// 用 r.dataJson() 里的 base_url/api_path 转发真实请求（lease_id 透传给上游语境）
client.report("llm", r.entryId(), r.leaseId(), success ? "SUCCESS" : "RETRYABLE_FAIL", units);
```

## 3. 红线（违反的后果都写在手册 §2.2）

1. **lease_id 必须随结果回填**：resolve 拿到的 `lease_id` 在本次调用结束后必须 report；
   不回填 = 租约占着并发额度直到 30s TTL 过期，上游容量被白吃。
2. **超时/异常也是结果**：读超时报 `RETRYABLE_FAIL`；确定性失败（4xx 语义、禁用该渠道）报
   `DISABLE_FAIL`（该会话即时脱离亲和）；成功报 `SUCCESS`。不报 = 同上。
3. **不要缓存 resolve 结果**：每次直连决议，failover = 重新 resolve；缓存 entry 会绕开容量与状态机。
4. **EMPTY 不是错误**：HTTP 200 + `code 0` + `entry_id=null` + `reasons[]`（全过滤/全冻结/表空）。
   按业务降级处理，禁止重试风暴。

## 4. 失败与补发语义

- report 部分拒收 → HTTP 200 + `code 10700` + `data.rejected[]` 明细（index/code/message）。
  **只补发 rejected 条目**，已受理的不要重发（计数不幂等）。SDK 的 `TrReportBatcher` 已内置补发。
- token-route 自身 Redis 故障：resolve 返回 EMPTY（不误动作）、report 返回 10700 提示补发——
  消费方按上述纪律重投即可，恢复后无需对账。

## 5. 鉴权选型

| 模式 | 配置（token-route 侧 `tr.auth.*`） | 适用 |
|---|---|---|
| none | `contract-mode: none` | 内网信任（开发/同 K8s network） |
| apikey | `contract-mode: apikey` + `TR_API_KEY`；gateway 侧 `TrRouteClient.apiKey(url, key)` | 跨信任边界最简 |
| jwt | `contract-mode: jwt`（fwk4j-accesstoken，policy `tr-client`） | 复用统一登录态 |

## 6. 可观测对接

- **指标**：`GET /actuator/prometheus`（Prometheus 抓取格式），SLI 九项对照见
  `SLI告警建议.md` §指标对照（resolve EMPTY 率 / P99 / FEED 失败率 / script 降级等）。
- **探活**：`GET /v1/ping`（契约面）/ `/actuator/health`。
- **运维查询**：`/v1/ops/*`（默认 jwt，policy `tr-admin`）查条目状态、决议日志、亲和事件。
- 告警规则直接抄 `SLI告警建议.md` 的阈值表。

## 7. 联调起步（10 分钟）

```bash
# 起本地栈（token-route + redis + mock FEED 上游）
docker compose up -d --build
# 跑通最小闭环（resolve → report → 亲和 HIT → batcher）
cd examples/gateway-demo && mvn compile exec:java
```

生产部署清单（密钥轮换 / AOF / 双实例）见 `docs/用户文档/配置手册.md` 与 `CODING-PROGRESS.md` 上线检查单。
