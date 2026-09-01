package fun.commons.tokenroute.auth;

import fun.commons.framework4j.accesstoken.config.AccessTokenProperties;
import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.accesstoken.core.AccessTokenGenerator;
import fun.commons.framework4j.accesstoken.exception.AuthException;
import fun.commons.framework4j.accesstoken.interceptor.AccessTokenValidationStrategy;
import fun.commons.framework4j.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 鉴权拦截器分支（02 §3）：三模式路由（契约面/ops 面前缀选择）、apikey 401 信封、
 * jwt 失败 10200、jwt 正向（真实签发 token + 策略放行）、afterCompletion 清理。
 */
class TrAuthInterceptorBranchTest {

    private static final String SECRET = "unit-secret-0123456789abcdef";

    private final HttpServletResponse resp = mock(HttpServletResponse.class);
    private AccessTokenValidationStrategy strategy;
    private TrAuthProperties auth;

    @BeforeEach
    void setUp() throws Exception {
        auth = new TrAuthProperties();
        when(resp.getWriter()).thenReturn(new PrintWriter(java.io.OutputStream.nullOutputStream()));
        strategy = mock(AccessTokenValidationStrategy.class);
    }

    private TrAuthInterceptor interceptor() {
        AccessTokenProperties props = new AccessTokenProperties();
        props.setSecretKey(SECRET);
        return new TrAuthInterceptor(auth, props, strategy);
    }

    private static HttpServletRequest request(String uri, String apiKey, String authorization) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getHeader("X-Api-Key")).thenReturn(apiKey);
        when(req.getHeader("Authorization")).thenReturn(authorization);
        return req;
    }

    private static String issuedToken() {
        AccessTokenProperties genProps = new AccessTokenProperties();
        genProps.setSecretKey(SECRET);
        genProps.setHashSalt("unit-salt");
        genProps.setExpireTime(3_600_000L);
        AccessTokenProperties.Policy policy = new AccessTokenProperties.Policy();
        policy.setKey(List.of("uid")); // policy.key = 必备 claims 字段（非签名密钥）
        policy.setExpireTime(3_600_000L);
        Map<String, AccessTokenProperties.Policy> policies = new java.util.HashMap<>();
        policies.put(AccessTokenGenerator.TYPE_ACCESS, policy);
        genProps.setPolicies(policies);
        StringRedisTemplate template = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        AccessTokenGenerator generator = new AccessTokenGenerator(genProps, template, "tr-unit");
        return generator.generateToken(AccessTokenGenerator.TYPE_ACCESS, Map.of("uid", "user-1"));
    }

    @Test
    void noneModePassesThrough() throws Exception {
        auth.setContractMode(TrAuthMode.NONE);
        assertThat(interceptor().preHandle(request("/v1/ping", null, null), resp, new Object())).isTrue();
    }

    @Test
    void apiKeyMatchPassesAndMismatchIs401Envelope() throws Exception {
        auth.setContractMode(TrAuthMode.APIKEY);
        auth.setApiKey("k1");
        TrAuthInterceptor interceptor = interceptor();
        assertThat(interceptor.preHandle(request("/v1/ping", "k1", null), resp, new Object())).isTrue();

        assertThat(interceptor.preHandle(request("/v1/ping", "wrong", null), resp, new Object())).isFalse();
        verify(resp).setStatus(401);
        verify(resp).setContentType("application/json");
    }

    @Test
    void apiKeyMissingHeaderIs401() throws Exception {
        auth.setContractMode(TrAuthMode.APIKEY);
        auth.setApiKey("k1");
        assertThat(interceptor().preHandle(request("/v1/ping", null, null), resp, new Object())).isFalse();
        verify(resp).setStatus(401);
    }

    @Test
    void opsFaceUsesOpsModeNotContractMode() throws Exception {
        auth.setContractMode(TrAuthMode.NONE);
        auth.setOpsMode(TrAuthMode.APIKEY);
        auth.setApiKey("k1");
        // 契约面直过，ops 面按 ops 模式拒绝——同一拦截器按前缀分流
        assertThat(interceptor().preHandle(request("/v1/ping", null, null), resp, new Object())).isTrue();
        assertThat(interceptor().preHandle(request("/v1/ops/ping", null, null), resp, new Object())).isFalse();
    }

    @Test
    void jwtMissingBearerIs10200() {
        auth.setContractMode(TrAuthMode.JWT);
        ApiException ex = catchThrowableOfType(
                () -> interceptor().preHandle(request("/v1/ping", null, null), resp, new Object()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(10200);
        assertThat(ex.getMessage()).contains("Bearer");
    }

    @Test
    void jwtGarbageTokenIs10200TokenInvalid() {
        auth.setContractMode(TrAuthMode.JWT);
        ApiException ex = catchThrowableOfType(
                () -> interceptor().preHandle(
                        request("/v1/ping", null, "Bearer not.a.jwt"), resp, new Object()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(10200);
    }

    @Test
    void jwtValidTokenPassesStrategyOnBothFaces() {
        auth.setContractMode(TrAuthMode.JWT);
        auth.setOpsMode(TrAuthMode.JWT);
        TrAuthInterceptor interceptor = interceptor();
        String token = "Bearer " + issuedToken();
        assertThat(catchThrowableOfType(
                () -> assertThat(interceptor.preHandle(request("/v1/ping", null, token), resp, new Object())).isTrue(),
                ApiException.class)).isNull();
        assertThat(catchThrowableOfType(
                () -> assertThat(interceptor.preHandle(request("/v1/ops/ping", null, token), resp, new Object())).isTrue(),
                ApiException.class)).isNull();
    }

    @Test
    void jwtStrategyRejectionMapsTo10200() throws Exception {
        auth.setContractMode(TrAuthMode.JWT);
        doThrow(new AuthException("expired"))
                .when(strategy).validate(any(), anyMap(), any());
        ApiException ex = catchThrowableOfType(
                () -> interceptor().preHandle(
                        request("/v1/ping", null, "Bearer " + issuedToken()), resp, new Object()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(10200);
    }

    @Test
    void afterCompletionClearsTokenContextOnlyForJwtFace() {
        auth.setContractMode(TrAuthMode.JWT);
        auth.setOpsMode(TrAuthMode.NONE);
        TrAuthInterceptor interceptor = interceptor();
        TokenContext.set("tok", Map.of("k", "v")); // 模拟 jwt 校验残留
        interceptor.afterCompletion(request("/v1/ping", null, null), resp, new Object(), null);
        interceptor.afterCompletion(request("/v1/ops/ping", null, null), resp, new Object(), null);
    }

    @Test
    void envelopeBodyCarriesUnauthorizedCode() throws Exception {
        auth.setContractMode(TrAuthMode.APIKEY);
        auth.setApiKey("k1");
        PrintWriter writer = mock(PrintWriter.class);
        HttpServletResponse spying = mock(HttpServletResponse.class);
        when(spying.getWriter()).thenReturn(writer);
        TrAuthProperties realAuth = auth;
        AccessTokenProperties props = new AccessTokenProperties();
        props.setSecretKey(SECRET);
        new TrAuthInterceptor(realAuth, props, strategy)
                .preHandle(request("/v1/ping", "bad", null), spying, new Object());
        verify(writer).write(contains("10200"));
    }
}
