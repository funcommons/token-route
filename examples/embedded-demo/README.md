# embedded-demo —— token-route 嵌入式核心最小宿主示例

`token-route-starter` 的可运行基准:**引依赖即自动装配**,`TrRouteEngine` 进程内直调走完
**resolve → admin 注入直查 → report → 亲和 HIT → detach 重绑** 闭环——全程零网络跳。
接入文档见 `docs/用户文档/03_嵌入式SDK接入指南.md`(本示例即该指南的完整落地)。
对照:HTTP 客户端模式见 [../gateway-demo](../gateway-demo/README.md)。

宿主形态三件套(任何嵌入式宿主都这三步):

1. **启动类排除链**(`EmbeddedDemoApplication`):fwk4j-redis 传递的 Boot/Redisson 自动装配排除;
2. **fwk4j MultiRedis datasource**(`application.yml`):`main` → 对应 `tr.redis-name`;
3. **表种子随宿主配置**(`tr.tables`,schema 与独立服务一致)。

## 运行(compose 一条命令,推荐)

```bash
# 仓库根目录(profile demo 不影响默认栈)
docker compose run --rm embedded-demo
```

跑通后输出:

```
① resolve: entry=e-xxxx affinity=NEW lease=… 上游载荷={base_url=…, …}
② admin 注入直查: found=true entry=e-xxxx 剩余TTL=28799s
③ report: accepted=1(lease 精确释放)
④ 再 resolve: entry=e-xxxx affinity=HIT
⑤ detach: true → 再 resolve: affinity=NEW(重绑)
DEMO OK — 嵌入式 resolve→admin→report→亲和→detach 闭环全部通过(零网络跳)
```

任一步断言失败抛 `DEMO FAIL: <步骤>` 非零退出——可直接当联调冒烟用。

## 运行(本机 Redis)

```bash
# 仓库根:starter 装进本地仓库
mvn -N install && mvn -pl token-route-starter -am install -DskipTests

# 本目录:指向本机 Redis 与任一 FEED 端点
TR_FEED_URL=http://localhost:8000/feed mvn -q spring-boot:run
```

环境变量:`REDIS_HOST`(默认 localhost)/ `REDIS_PORT`(6379)/ `TR_FEED_URL`/ `TR_TABLE`(默认 llm)。

## 与独立服务混布

示例与 compose 里的 `app`(9302)共用同一 Redis 键空间(`tr.*`)——demo 建立的亲和/记账
对独立服务立即可见,反之亦然(指南 §7 混布语义)。
