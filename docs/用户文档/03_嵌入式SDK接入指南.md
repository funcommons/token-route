# token-route 嵌入式 SDK 接入指南

> **本册自包含**:Java 服务进程内嵌入 token-route 路由核心(`token-route-starter`),零网络跳。
> 设计依据 `docs/开发文档/01_设计方案.md` V2.3 §7.2;字段/契约同 `02_接口契约`(嵌入式与 HTTP 模式共享同一内核与 Redis 状态面,语义完全一致)。
> 适用对象:Java 17 + Spring Boot 3.x + fwk4j 族的消费方服务(如 MMagiX channel-domain)。
> 同目录配套:[01_消费方接入手册](./01_消费方接入手册.md)(HTTP 模式 + 领域语义)· [配置手册](./配置手册.md)(表种子字段表)· [FAQ](./FAQ.md)。

## 0. 三十秒了解:嵌入式是什么、适合谁

**引一个 Maven 依赖,路由引擎进你的进程**——resolve/report/亲和/状态机/容量治理全部在本地方法调用里完成,不再有 `http://token-route:9302` 这一跳。状态仍在 Redis(Lua 原子落账),多实例/多服务共享同一份路由状态。

| | HTTP 模式(token-route-app + sdk) | **嵌入式模式(token-route-starter)** |
|---|---|---|
| 路由调用 | 每次上游调用 +1 网络跳(resolve P99 ≤ 20ms) | **进程内直调(微秒级)** |
| 部署面 | 独立微服务 ×2 实例 + Redis | 只有 Redis(引擎随宿主进程) |
| 语言 | 任意(HTTP) | **Java 17 + Spring Boot 3.x** |
| 升级 | token-route 独立发版滚动重启 | **随宿主一起发版** |
| 鉴权 | 契约面三模式(none/apikey/jwt) | 无鉴权面(进程内即边界) |
| 管理操作 | 内部管理面 HTTP(内部令牌) | 直接注入 admin 服务(进程内调用) |
| 适用 | 多消费方共享、非 Java、运维面隔离 | **单一消费方深度集成、去网络跳** |

**适合你,如果**:消费方是族内 Java 服务、想要最低延迟、或不想多运维一个微服务。
**不适合,如果**:多个异构消费方要共享路由(用独立服务)、宿主发版节奏很慢(状态机修复要等宿主)。

## 1. 引入依赖

```xml
<dependency>
  <groupId>fun.commons</groupId>
  <artifactId>token-route-starter</artifactId>
  <version>1.1.0</version>   <!-- 对齐 token-route 发版 -->
</dependency>
```

获取方式(任选):
- **私仓/本地**:`mvn install` token-route 仓(根 pom)后从内部仓库消费;
- **jitpack**:`com.github.funcommons.token-route:token-route-starter:<tag>`(仓已挂 jitpack 仓库源)。

> 依赖面:jackson / Groovy(脚本引擎)/ fwk4j-web·redis / micrometer——会进宿主 classpath;fwk4j-redis 传递 `redisson-spring-boot-starter`,其自动装配排除链见 §2。

## 2. 宿主前置(一次性)

1. **Redis datasource**:宿主配 fwk4j MultiRedis(嵌入式引擎用它拿 StringRedisTemplate 与 Redisson):

```yaml
framework4j:
  redis:
    enabled: true
    datasources:
      main:                      # 与 tr.redis-name 对应(缺省 main)
        host: ${TR_REDIS_HOST}
        port: ${TR_REDIS_PORT:6379}
        timeout: 3s
        redisson:
          enabled: true          # 多实例宿主必开:评估器多实例互斥(单实例可省)
```

2. **排除链**(族内惯例,对齐 token-route-app 启动类):fwk4j-redis 传递的 Boot/Redisson 自动装配与本库装配方式冲突,宿主启动类排除:

```java
@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.redis.RedisAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class,
        org.redisson.spring.starter.RedissonAutoConfigurationV2.class,   // Redisson 4.6.1:V2/V4 二选一,旧类会启动失败
        org.redisson.spring.starter.RedissonAutoConfigurationV4.class
})
public class HostApplication { }
```

3. **Boot 依赖管理**:宿主用 `spring-boot-starter-parent` 或 `dependencyManagement` 引 `spring-boot-dependencies` BOM——netty/lettuce 等传递依赖需 BOM 锁版,裸引会出现 `NoClassDefFoundError: SocketProtocolFamily` 类版本错配;
4. 宿主若自行启用 fwk4j-web 全局异常处理,须自备 `spring-jdbc`(上游已知问题 funcommons/framework4j#19)。

## 3. 配置(随宿主 application.yml)

```yaml
tr:
  enabled: true                  # 总开关(缺省 true——引依赖即装配;false 整体关闭)
  key-prefix: tr                 # Redis 键前缀(与其他部署共用 Redis 时按环境区分,见 §7)
  redis-name: main               # fwk4j MultiRedis datasource 名
  scheduler:
    enabled: true                # 评估器(状态机每分钟驱动;自管 daemon 线程,不动宿主 @EnableScheduling 语义)
  engine:
    script-timeout-ms: 100       # 脚本执行超时
  tables:                        # 表种子——字段为扁平绑定(配置手册 §3 字段表)
    - name: llm-supply
      strategy-type: WEIGHTED_RANDOM
      affinity-enabled: true
      affinity-idle-timeout-seconds: 28800
      refresh-url: http://supply-system:9100/v1/routes/llm-channels
      refresh-interval-seconds: 60
```

- **表种子是宿主配置的一部分**:换表/调参 = 改宿主配置 + 滚动重启宿主(与独立服务的纪律一致);坏种子**启动即失败**(fail-fast,10633 指名表);
- 脚本文件路径(`file:/conf/tr-scripts/xx.groovy`)随宿主制品版本化。

## 4. 快速开始(TrRouteEngine 门面)

```java
@Service
public class RoutingService {

    private final TrRouteEngine engine;   // 自动装配注入

    public RoutingService(TrRouteEngine engine) {
        this.engine = engine;
    }

    public void handleTask(String taskId) {
        // ① 取路由(EMPTY 不是异常:entry_id=null 按 reasons 处置,同接入手册 §8.2)
        TrResolveResponse r = engine.resolve("llm-supply", taskId, Map.of("model", "gpt-4o"), "my-service");
        if (r.getEntryId() == null) {
            throw new NoUpstreamAvailableException(r.getReasons());
        }

        // ② 用 data_json 载荷调用真实上游(leaseId 存入调用上下文,③ 回传)
        String baseUrl = (String) r.getDataJson().get("base_url");
        // ... http 调用 ...

        // ③ 回填三态(进程内直调;高频路径可自建批量缓冲,纪律同接入手册 §2.2/§4.2)
        TrReportRequest.Item item = new TrReportRequest.Item();
        item.setEntryId(r.getEntryId());
        item.setLeaseId(r.getLeaseId());
        item.setResult("SUCCESS");
        item.setRateUnits(1.0);
        item.setSessionId(taskId);
        TrReportRequest report = new TrReportRequest();
        report.setReports(java.util.List.of(item));
        engine.report(report);

        // ④ 会话终态(亲和表建议):立即换条目也是这个组合(detach → resolve)
        engine.detach("llm-supply", taskId);
    }
}
```

与 HTTP 模式的**语义完全一致**(同一内核):lease_id 纪律、report 三态判定、EMPTY reasons、亲和 HIT/NEW/自动脱离、容量过滤——对接入手册 §1~§8 的领域语义章节照读即可,只是调用从 HTTP 换成方法调用。

## 5. 管理能力(进程内注入,无鉴权面)

运营管理操作直接注入服务(不需要也不走内部令牌——进程边界即边界;审计日志照落):

```java
// 亲和管理(运营手动切流,TR-ADM-001~004)
private final TrAffinityAdminService affinityAdmin;
Map<String, Object> out = affinityAdmin.set("llm-supply", "42:gpt-4o", "e-1a2b", 3600, sourceIp);
Map<String, Object> now = affinityAdmin.get("llm-supply", "42:gpt-4o");

// 状态重置(余额不足停用 → 充值后手动恢复,TR-ADM-005)
private final TrStateAdminService stateAdmin;
Map<String, Object> rst = stateAdmin.reset("llm-supply", "e-1a2b", sourceIp);
```

> 宿主的运营入口(管理台回调等)自行包一层;`by` 参数建议传操作来源(落审计日志 `[TR-AFFINITY-ADMIN]` / `[TR-STATE-ADMIN]`)。

## 6. 指标与观测

- 宿主 classpath 有 `MeterRegistry`(actuator 环境)→ **自动埋点**,指标与独立服务同名:`tr.resolve` / `tr.resolve.latency` / `tr.report` / `tr.state.transition` / `tr.feed.pull` / `tr.evaluator.run`(对照 SLI 告警建议);
- 无 MeterRegistry → 全部 noop,零开销;
- 决议/状态/亲和事件照写 Redis ring(热窗 7d)与 `[TR-RESOLVE]` 结构化日志。

## 7. 共享 Redis 语义(混布)

状态**全在 Redis**:嵌入式宿主、独立 token-route 服务可以指向同一 Redis **共用同一键空间**(`tr.*`)——条目/亲和/状态机互相可见,FEED 数据一份。

- 多实例宿主:开 Redisson 后评估器自动多实例互斥(每轮仅一实例执行,语义同独立服务双实例);
- 环境隔离:用 `tr.key-prefix` 区分(如 `tr-staging`);
- **混布注意**:嵌入式与独立服务共用时,`tr.tables` 种子需两边一致(表壳在各自进程内存,不一致会出现「一边认表一边不认」)。

## 8. 纪律与红线(与 HTTP 模式共用 + 嵌入式特有)

| # | 纪律 |
|---|---|
| 1 | lease_id 纪律不变:resolve → 真实调用 → report 回传释放(接入手册 §2.2 红线全表照用) |
| 2 | **升级随宿主**:路由内核修复(如 Lua/状态机)要等宿主发版——评估宿主发版节奏能否接受 |
| 3 | 表种子变更 = 宿主滚动重启(零管理写面原则在嵌入式同样成立) |
| 4 | FEED 上游仍须提供 refresh_url GET 端点(拉取自愈与独立服务一致) |
| 5 | Redis 故障语义:resolve 返回 EMPTY 不抛错不自愈误动作(内核统一兜底);宿主降级预案责任同 HTTP 模式(接入手册 §3.6) |
| 6 | 不建议在同一宿主同时嵌 starter 又以 HTTP 调独立服务操作**同一张表**——可行但两份表种子要严格同步 |

## 9. 速查

| 症状 | 根因/处置 |
|---|---|
| 启动报 `MultiRedisManager` bean 缺失 | 宿主未装 fwk4j-redis / 排除链误伤——核对 §2 |
| 启动报 10633 指名某表 | 表种子 schema 错(缺 name/refresh_url 等)——按报错字段修配置 |
| Redisson 自动装配冲突启动失败 | 排除链不完整(V2/V4 按 Redisson 版本二选一) |
| 评估器不跑 | `tr.scheduler.enabled=false`;或 Redisson 未开且多实例抢跑(单实例本地语义仍会跑) |
| 想临时整体停用 | `tr.enabled=false`(bean 全下,可留依赖不发) |
