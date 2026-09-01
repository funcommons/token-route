package fun.commons.tokenroute.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 默认模式（契约面 none / ops 面 jwt）下两面 ping + 统一信封（07 S0 出口闸门 ①②③）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrPingContractTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void contractFacePassesThroughInNoneMode_withEnvelope() throws Exception {
        // ①信封 + ③契约面按模式（dev none 直过）
        String body = mvc.perform(get("/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.service").value("token-route"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("trace");
    }

    @Test
    void opsFaceDefaultsToJwtAndRejectsWithoutToken() throws Exception {
        // ②ops 面 10200（ops-mode 默认 jwt，无 token → 200 + 10200）
        mvc.perform(get("/v1/ops/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10200));
    }
}
