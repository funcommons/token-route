package fun.commons.tokenroute.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 亲和管理内部鉴权分支（issue #1 验收：内部鉴权拒绝）：
 * 未配置令牌 = 端点禁用 / 令牌缺失·不符 401 / 可信 IP 拒绝 403 / 令牌 + IP 双过直通。
 */
class TrAffinityAdminInterceptorTest {

    private final TrAuthProperties props = new TrAuthProperties();

    private boolean preHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
        return new TrAffinityAdminInterceptor(props).preHandle(request, response, new Object());
    }

    @Test
    void unconfiguredTokenDisablesEndpoint() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(preHandle(req("any"), resp)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void missingOrWrongTokenIs401() throws Exception {
        props.getAffinityAdmin().setInternalToken("tok-1");

        MockHttpServletResponse missing = new MockHttpServletResponse();
        assertThat(preHandle(req(null), missing)).isFalse();
        assertThat(missing.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

        MockHttpServletResponse wrong = new MockHttpServletResponse();
        MockHttpServletRequest wrongReq = req("tok-2");
        assertThat(preHandle(wrongReq, wrong)).isFalse();
        assertThat(wrong.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void correctTokenPassesWithoutIpList() throws Exception {
        props.getAffinityAdmin().setInternalToken("tok-1");
        assertThat(preHandle(req("tok-1"), new MockHttpServletResponse())).isTrue();
    }

    @Test
    void trustedIpListRejectsUnknownSource() throws Exception {
        props.getAffinityAdmin().setInternalToken("tok-1");
        props.getAffinityAdmin().setTrustedIps(List.of("10.0.0.1"));

        MockHttpServletRequest fromElsewhere = req("tok-1");
        fromElsewhere.setRemoteAddr("192.168.1.9");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(preHandle(fromElsewhere, resp)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void trustedIpListPassesListedSource() throws Exception {
        props.getAffinityAdmin().setInternalToken("tok-1");
        props.getAffinityAdmin().setTrustedIps(List.of("10.0.0.1"));

        MockHttpServletRequest fromTrusted = req("tok-1");
        fromTrusted.setRemoteAddr("10.0.0.1");
        assertThat(preHandle(fromTrusted, new MockHttpServletResponse())).isTrue();
    }

    @Test
    void rejectBodyCarriesEnvelope() throws Exception {
        props.getAffinityAdmin().setInternalToken("tok-1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(preHandle(req("bad"), resp)).isFalse();
        assertThat(resp.getContentAsString()).contains("\"code\":10200");
    }

    private MockHttpServletRequest req(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/admin/affinity/set");
        request.setRemoteAddr("127.0.0.1");
        if (token != null) {
            request.addHeader(TrAffinityAdminInterceptor.TOKEN_HEADER, token);
        }
        return request;
    }
}
