package fun.commons.tokenroute.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * jwt 模式失败语义（02 §3）：缺 token / 篡改 token → HTTP 200 + code 10200（两面同语义）。
 * 正向 jwt 校验依赖 Redis 会话，归入 Testcontainers IT（S2 起）与 compose 冒烟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tr.auth.contract-mode=jwt",
        "tr.auth.ops-mode=jwt"
})
class TrAuthJwtModeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void missingBearerTokenIs10200() throws Exception {
        mvc.perform(get("/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10200));
    }

    @Test
    void tamperedTokenIs10200() throws Exception {
        mvc.perform(get("/v1/ping").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10200));
    }

    @Test
    void opsFaceRejectsWithoutToken() throws Exception {
        mvc.perform(get("/v1/ops/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10200));
    }
}
