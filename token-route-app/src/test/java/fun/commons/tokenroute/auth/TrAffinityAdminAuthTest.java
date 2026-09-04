package fun.commons.tokenroute.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 亲和管理端点鉴权矩阵（issue #1 验收：内部鉴权拒绝）：
 * 令牌缺失/不符 401、可信 IP 拒绝 403、双过放行；
 * 且 /v1/admin/** 不落入契约面/ops 面三模式（独立拦截器，TrConfig 互斥注册）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tr.auth.contract-mode=apikey",
        "tr.auth.api-key=secret-1",
        "tr.auth.affinity-admin.internal-token=tok-1",
        "tr.auth.affinity-admin.trusted-ips=10.0.0.1"
})
class TrAffinityAdminAuthTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(get("/v1/admin/affinity/get")
                        .queryParam("table_id", "llm").queryParam("session_id", "s1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10200));
    }

    @Test
    void wrongTokenIs401() throws Exception {
        mvc.perform(get("/v1/admin/affinity/get")
                        .queryParam("table_id", "llm").queryParam("session_id", "s1")
                        .header("X-Internal-Service-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contractApiKeyDoesNotOpenAdminFace() throws Exception {
        // 契约面凭证不是内部令牌：admin 面不被三模式放行（面间互不可越）
        mvc.perform(get("/v1/admin/affinity/get")
                        .queryParam("table_id", "llm").queryParam("session_id", "s1")
                        .header("X-Api-Key", "secret-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void trustedIpMismatchIs403() throws Exception {
        mvc.perform(get("/v1/admin/affinity/get")
                        .queryParam("table_id", "llm").queryParam("session_id", "s1")
                        .header("X-Internal-Service-Token", "tok-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenAndTrustedIpPassThroughToController() throws Exception {
        // 双过 → 放行至控制器（dev 种子表 llm 未有该会话绑定 → found=false，code 0）
        mvc.perform(get("/v1/admin/affinity/get")
                        .queryParam("table_id", "llm").queryParam("session_id", "s1")
                        .header("X-Internal-Service-Token", "tok-1")
                        .with(remoteAddr("10.0.0.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.found").value(false));
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
