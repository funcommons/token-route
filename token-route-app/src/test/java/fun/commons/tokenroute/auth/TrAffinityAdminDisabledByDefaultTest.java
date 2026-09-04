package fun.commons.tokenroute.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全缺省：internal-token 未配置 = 亲和管理端点整体禁用——任意令牌一律 401（防裸奔上线，02 §3.3）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrAffinityAdminDisabledByDefaultTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void anyTokenIsRejectedWhenUnconfigured() throws Exception {
        mvc.perform(get("/v1/admin/affinity/get")
                        .queryParam("table_id", "llm").queryParam("session_id", "s1")
                        .header("X-Internal-Service-Token", "whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10200));
    }
}
