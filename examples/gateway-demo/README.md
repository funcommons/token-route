# gateway-demo —— token-route 最小消费方示例

token-gateway 接入 token-route 的可运行基准：SDK 走完 **resolve → report → 亲和 HIT → 批量回填 → detach → fallback** 闭环。
背景见 `docs/用户文档/token-gateway接入指引.md`，完整契约见 `docs/用户文档/01_消费方接入手册.md`。

## 前置

1. token-route 栈已起（仓库根目录）：

   ```bash
   docker compose up -d --build
   ```

2. SDK 安装到本地仓库（示例按本地坐标引用）：

   ```bash
   # 仓库根目录
   mvn -N install
   mvn -pl token-route-sdk install -DskipTests
   ```

## 运行

```bash
mvn -q compile exec:java
```

环境变量：`TR_BASE`（默认 `http://localhost:9302`）、`TR_TABLE`（默认 `llm`，dev 种子表）。

## 期望输出

```
① resolve: entry=e-xxxx affinity=NEW lease=… 上游载荷={base_url=…, …}
② report: accepted=1（lease 精确释放）
③ 再 resolve: entry=e-xxxx affinity=HIT
④ batcher 批量回填完成（缓冲/定时双触发，rejected 自动补发）
⑤ detach: demo-task-…
⑥ fallback: e-xxxx DISABLE_FAIL 摘除 → 重选 e-yyyy（解冻=FEED 显式 ACTIVE，运维面操作）
DEMO OK — resolve→report→亲和→批量回填→fallback 闭环全部通过
```

> ⑥ 为任务面 fallback 用例（issue #4 R3 验收）：持续性不可用 → `DISABLE_FAIL` 立即 FROZEN →
> 下一次 resolve 确定性换上游;偶发失败请用 `RETRYABLE_FAIL`（降权排水，不摘除）。

任一步断言失败抛 `DEMO FAIL: <步骤>` 并以非零码退出——可直接当联调冒烟用。
