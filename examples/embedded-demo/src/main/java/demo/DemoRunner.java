package demo;

import fun.commons.tokenroute.TrRouteEngine;
import fun.commons.tokenroute.admin.TrAffinityAdminService;
import fun.commons.tokenroute.report.TrReportRequest;
import fun.commons.tokenroute.report.TrReportResponse;
import fun.commons.tokenroute.resolve.TrResolveResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 嵌入式闭环示例（与 examples/gateway-demo 同一套断言节奏，但全程进程内直调，零网络跳）：
 * ① resolve 预占（affinity=NEW，冷启动自动回源 FEED）
 * → ② 管理服务进程内注入演示（TrAffinityAdminService.get 直查绑定）
 * → ③ report SUCCESS（lease 精确释放）
 * → ④ 同会话再 resolve（affinity=HIT 同条目）
 * → ⑤ detach 清理 → 再 resolve 重绑 NEW。
 *
 * 运行见 examples/embedded-demo/README.md（compose profile demo 或本机 Redis）。
 */
@Component
public class DemoRunner {

    private final TrRouteEngine engine;
    private final TrAffinityAdminService affinityAdmin;

    public DemoRunner(TrRouteEngine engine, TrAffinityAdminService affinityAdmin) {
        this.engine = engine;
        this.affinityAdmin = affinityAdmin;
    }

    public int runDemo() {
        String table = env("TR_TABLE", "llm");
        String task = "embedded-" + System.currentTimeMillis();

        // ① 取路由：进程内方法直调；EMPTY 时 entry_id=null（按 reasons 降级，本例 mock FEED 必有条目）
        TrResolveResponse r1 = engine.resolve(table, task, Map.of(), "embedded-demo");
        must(r1.getEntryId() != null, "① resolve 命中条目（EMPTY 时按 reasons 处置）");
        System.out.println("① resolve: entry=" + r1.getEntryId() + " affinity=" + r1.getAffinity()
                + " lease=" + r1.getLeaseId() + " 上游载荷=" + r1.getDataJson());

        // ② 管理能力=进程内注入（无 HTTP/无内部令牌）：直查刚建立的亲和绑定
        Map<String, Object> bound = affinityAdmin.get(table, task);
        must(Boolean.TRUE.equals(bound.get("found")) && r1.getEntryId().equals(bound.get("entry_id")),
                "② admin.get 直查绑定 found/entry 应命中");
        System.out.println("② admin 注入直查: found=" + bound.get("found")
                + " entry=" + bound.get("entry_id") + " 剩余TTL=" + bound.get("ttl_remaining_seconds") + "s");

        // ③ 回填 SUCCESS：lease 精确释放 + 状态机记账
        TrReportRequest.Item item = new TrReportRequest.Item();
        item.setEntryId(r1.getEntryId());
        item.setLeaseId(r1.getLeaseId());
        item.setResult("SUCCESS");
        item.setRateUnits(1.0);
        item.setSessionId(task);
        TrReportRequest report = new TrReportRequest();
        report.setReports(new java.util.ArrayList<>(java.util.List.of(item)));
        TrReportResponse resp = engine.report(report);
        must(resp.getAccepted() == 1 && resp.getRejected().isEmpty(), "③ report SUCCESS 受理");
        System.out.println("③ report: accepted=" + resp.getAccepted() + "（lease 精确释放）");

        // ④ 同会话再取路由：亲和 HIT 回同一上游（进程内判定）
        TrResolveResponse r2 = engine.resolve(table, task, Map.of(), "embedded-demo");
        must("HIT".equals(r2.getAffinity()) && r2.getEntryId().equals(r1.getEntryId()),
                "④ 同会话应 HIT 且同条目，实际 " + r2.getAffinity() + "/" + r2.getEntryId());
        System.out.println("④ 再 resolve: entry=" + r2.getEntryId() + " affinity=" + r2.getAffinity());

        // ⑤ 任务终态：detach 清理（幂等）→ 再 resolve 视为新会话重绑
        reportSuccess(r2);
        boolean detached = engine.detach(table, task);
        must(detached && !engine.detach(table, task), "⑤ detach 幂等（第一次 true，第二次 false）");
        TrResolveResponse r3 = engine.resolve(table, task, Map.of(), "embedded-demo");
        must("NEW".equals(r3.getAffinity()), "⑤ detach 后再 resolve 应重绑 NEW");
        reportSuccess(r3);
        System.out.println("⑤ detach: true → 再 resolve: affinity=" + r3.getAffinity() + "（重绑）");

        System.out.println("DEMO OK — 嵌入式 resolve→admin→report→亲和→detach 闭环全部通过（零网络跳）");
        return 0;
    }

    private void reportSuccess(TrResolveResponse r) {
        TrReportRequest.Item i = new TrReportRequest.Item();
        i.setEntryId(r.getEntryId());
        i.setLeaseId(r.getLeaseId());
        i.setResult("SUCCESS");
        i.setRateUnits(1.0);
        TrReportRequest req = new TrReportRequest();
        req.setReports(new java.util.ArrayList<>(java.util.List.of(i)));
        engine.report(req);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private static void must(boolean cond, String step) {
        if (!cond) {
            System.err.println("DEMO FAIL: " + step);
            throw new IllegalStateException("DEMO FAIL: " + step);
        }
    }
}
