package demo;

import fun.commons.tokenroute.client.TrReportBatcher;
import fun.commons.tokenroute.client.TrRouteClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * token-gateway 接入 token-route 的最小闭环示例：
 * ① resolve 预占（affinity=NEW）→ ② report SUCCESS（lease 精确释放）
 * → ③ 同会话再 resolve（affinity=HIT 回同一上游）→ ④ TrReportBatcher 批量回填
 * → ⑤ detach 清理会话亲和。
 *
 * 前置：token-route 栈已起（仓库根 `docker compose up -d`），dev 种子含 llm 表（mock FEED 两条目）。
 * SDK 先安装到本地仓库：仓库根 `mvn -N install && mvn -pl token-route-sdk install -DskipTests`。
 * 运行：本目录 `mvn -q compile exec:java`（TR_BASE / TR_TABLE 环境变量可覆盖，默认 localhost:9302 / llm）。
 */
public class GatewayDemo {

    public static void main(String[] args) throws Exception {
        String base = env("TR_BASE", "http://localhost:9302");
        String table = env("TR_TABLE", "llm");
        TrRouteClient client = TrRouteClient.open(base).callerId("gateway-demo");

        // ① 任务启动：session_id = 任务 ID；resolve 返回条目 + 并发租约
        String task = "demo-task-" + System.currentTimeMillis();
        var r1 = client.resolve(table, task, Map.of());
        must(r1.isOk(), "① resolve 信封 code=0");
        var d1 = r1.getData();
        must(d1.getEntryId() != null, "① resolve 命中条目（EMPTY 时 entry_id=null，按 reasons 降级）");
        System.out.println("① resolve: entry=" + d1.getEntryId() + " affinity=" + d1.getAffinity()
                + " lease=" + d1.getLeaseId() + " 上游载荷=" + d1.getDataJson());

        // ② 用载荷调用真实上游（此处模拟成功）→ report SUCCESS，租约精确释放
        var rep = client.report(List.of(Map.of(
                "entry_id", d1.getEntryId(),
                "lease_id", d1.getLeaseId(),
                "result", "SUCCESS",
                "rate_units", 1.0)));
        must(rep.isOk() && rep.getData().getAccepted() == 1, "② report SUCCESS 受理");
        System.out.println("② report: accepted=" + rep.getData().getAccepted() + "（lease 精确释放）");

        // ③ 同一任务再取路由：亲和 HIT，回同一上游（连接/会话复用）
        var d2 = client.resolve(table, task, Map.of()).getData();
        must("HIT".equals(d2.getAffinity()) && d2.getEntryId().equals(d1.getEntryId()),
                "③ 同会话再 resolve 应亲和 HIT 且同条目，实际 " + d2.getAffinity() + "/" + d2.getEntryId());
        System.out.println("③ 再 resolve: entry=" + d2.getEntryId() + " affinity=" + d2.getAffinity());

        // ④ 批量回填（TrReportBatcher）：rejected 只补发 rejected，整批重发会污染状态机计数
        var d3 = client.resolve(table, task + "-b", Map.of()).getData();
        must(d3.getEntryId() != null, "④ 第二任务 resolve");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("entry_id", d3.getEntryId());
        item.put("lease_id", d3.getLeaseId());
        item.put("result", "SUCCESS");
        item.put("rate_units", 1.0);
        TrReportBatcher<Map<String, Object>> batcher = new TrReportBatcher<>(100, 500, batch -> {
            try {
                var env = client.report(batch);
                List<Map<String, Object>> rejectedIdx = List.of();
                return env.getData().getRejected().isEmpty() ? rejectedIdx : batch; // demo：单条不拒收，仅示意
            } catch (Exception e) {
                return batch; // 网络故障整批回炉重投
            }
        });
        batcher.add(item);
        batcher.flush();
        batcher.close();
        System.out.println("④ batcher 批量回填完成（缓冲/定时双触发，rejected 自动补发）");

        // ⑤ 任务终态：detach 清理会话亲和（幂等）；下次同任务 resolve 视为新会话
        client.detach(table, task);
        System.out.println("⑤ detach: " + task);

        // ⑥ 任务面 fallback（issue #4 R3 验收）：上游持续性不可用（渠道封禁/能力缺失）
        //    → DISABLE_FAIL 立即摘除（服务端 FROZEN）→ 下一次 resolve 确定性换到另一上游
        var f1 = client.resolve(table, task + "-f", Map.of());
        must(f1.isOk() && f1.getData().getEntryId() != null, "⑥ fallback 首 resolve");
        client.report(List.of(Map.of(
                "entry_id", f1.getData().getEntryId(),
                "lease_id", f1.getData().getLeaseId(),
                "result", "DISABLE_FAIL",        // 持续性不可用才用（偶发失败用 RETRYABLE_FAIL）
                "rate_units", 0.0,               // 调用前确认不可用，未耗上游资源
                "session_id", task + "-f")));
        var f2 = client.resolve(table, task + "-f2", Map.of());
        must(f2.isOk() && f2.getData().getEntryId() != null
                        && !f2.getData().getEntryId().equals(f1.getData().getEntryId()),
                "⑥ 摘除后应换到另一上游（mock FEED 恰两条目，确定性）");
        System.out.println("⑥ fallback: " + f1.getData().getEntryId() + " DISABLE_FAIL 摘除 → 重选 "
                + f2.getData().getEntryId() + "（解冻=FEED 显式 ACTIVE，运维面操作）");

        System.out.println("DEMO OK — resolve→report→亲和→批量回填→fallback 闭环全部通过");
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private static void must(boolean cond, String step) {
        if (!cond) {
            throw new IllegalStateException("DEMO FAIL: " + step);
        }
    }
}
