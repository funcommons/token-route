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
 * apikey 模式矩阵（02 §3）：缺头 401 / 错 key 401 / 对 key 直过；ops 面默认 jwt 不受影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tr.auth.contract-mode=apikey",
        "tr.auth.api-key=secret-1"
})
class TrAuthApikeyModeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void missingHeaderIs401() throws Exception {
        mvc.perform(get("/v1/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10200));
    }

    @Test
    void wrongKeyIs401() throws Exception {
        mvc.perform(get("/v1/ping").header("X-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correctKeyPasses() throws Exception {
        mvc.perform(get("/v1/ping").header("X-Api-Key", "secret-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void opsFaceStillUsesItsOwnMode() throws Exception {
        // 两面模式独立：契约面 apikey 通过后，ops 面仍按 jwt 拒绝无 token 请求
        mvc.perform(get("/v1/ops/ping").header("X-Api-Key", "secret-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10200));
    }
}
