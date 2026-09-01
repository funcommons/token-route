package fun.commons.tokenroute.auth;

import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.accesstoken.config.AccessTokenProperties;
import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.accesstoken.core.AccessTokenGenerator;
import fun.commons.framework4j.accesstoken.exception.AuthException;
import fun.commons.framework4j.accesstoken.interceptor.AccessTokenValidationStrategy;
import fun.commons.framework4j.accesstoken.util.TokenUtils;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.common.TrCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * 鉴权模式拦截器（02_接口契约 §3）：按路径前缀区分两面，各面独立应用配置的三模式。
 * <ul>
 *   <li>none → 直过（网络隔离即边界）</li>
 *   <li>apikey → X-Api-Key 比对，不符 → HTTP 401 + 信封</li>
 *   <li>jwt → 复用 fwk4j-accesstoken 校验（TokenUtils.parseToken + AccessTokenValidationStrategy，
 *       对齐框架 TokenInterceptor 流程，只验不签），失败 → 200 + 10200</li>
 * </ul>
 */
public class TrAuthInterceptor implements HandlerInterceptor {

    /** 契约面 policy（02_接口契约 §3） */
    public static final String POLICY_CONTRACT = "tr-client";
    /** 运维面 policy */
    public static final String POLICY_OPS = "tr-admin";

    private static final String OPS_PREFIX = "/v1/ops/";
    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TrAuthProperties auth;
    private final AccessTokenProperties accessTokenProperties;
    private final AccessTokenValidationStrategy jwtStrategy;

    public TrAuthInterceptor(TrAuthProperties auth,
                             AccessTokenProperties accessTokenProperties,
                             AccessTokenValidationStrategy jwtStrategy) {
        this.auth = auth;
        this.accessTokenProperties = accessTokenProperties;
        this.jwtStrategy = jwtStrategy;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        TrAuthMode mode = request.getRequestURI().startsWith(OPS_PREFIX)
                ? auth.getOpsMode()
                : auth.getContractMode();
        return switch (mode) {
            case NONE -> true;
            case APIKEY -> checkApiKey(request, response);
            case JWT -> checkJwt(request);
        };
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        // 对齐 fwk4j TokenInterceptor：jwt 校验可能写入 TokenContext，请求结束必须清理
        TrAuthMode mode = request.getRequestURI().startsWith(OPS_PREFIX)
                ? auth.getOpsMode()
                : auth.getContractMode();
        if (mode == TrAuthMode.JWT) {
            TokenContext.clear();
        }
    }

    private boolean checkApiKey(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String key = request.getHeader(API_KEY_HEADER);
        if (key != null && key.equals(auth.getApiKey())) {
            return true;
        }
        // 02 §3：apikey 不符 → HTTP 401；body 仍走统一信封（10200 认证失败）
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"code\":" + TrCode.UNAUTHORIZED.getCode()
                        + ",\"message\":\"" + TrCode.UNAUTHORIZED.getMessage()
                        + "\",\"data\":null,\"error\":[],\"trace_id\":\"\",\"timestamp\":" + System.currentTimeMillis() + "}");
        return false;
    }

    private boolean checkJwt(HttpServletRequest request) throws Exception {
        // 对齐 fwk4j TokenInterceptor：parseToken(secret) → strategy.validate(注解, claims, request)
        boolean policyOps = request.getRequestURI().startsWith(OPS_PREFIX);
        RequiresToken required = syntheticRequiresToken(policyOps ? POLICY_OPS : POLICY_CONTRACT);
        try {
            Map<String, Object> claims = TokenUtils.parseToken(bearerToken(request),
                    accessTokenProperties.getSecretKey());
            jwtStrategy.validate(required, claims, request);
            return true;
        } catch (AuthException e) {
            // jwt 失败语义（02 §3）：HTTP 200 + 10200
            throw new ApiException(TrCode.UNAUTHORIZED.getCode(), e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // 解析失败 / 超时 / 篡改 → 10200
            throw new ApiException(ApiCode.UNAUTHORIZED, "token 无效");
        }
    }

    private String bearerToken(HttpServletRequest request) {
        String h = request.getHeader(AUTH_HEADER);
        if (h == null || !h.startsWith(BEARER_PREFIX) || h.length() <= BEARER_PREFIX.length()) {
            throw new ApiException(ApiCode.UNAUTHORIZED, "缺少 Bearer token");
        }
        return h.substring(BEARER_PREFIX.length());
    }

    /** 合成 @RequiresToken（fwk4j 校验策略以注解为参数载体） */
    private RequiresToken syntheticRequiresToken(String policy) {
        return (RequiresToken) Proxy.newProxyInstance(
                RequiresToken.class.getClassLoader(),
                new Class<?>[]{RequiresToken.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "value" -> policy;
                    case "type" -> AccessTokenGenerator.TYPE_ACCESS;
                    case "roles", "anyRole" -> new String[0];
                    case "exception" -> AuthException.class;
                    case "toString", "hashCode", "equals" -> method.invoke(new RequiresToken() {
                        @Override public String value() { return policy; }
                        @Override public String type() { return AccessTokenGenerator.TYPE_ACCESS; }
                        @Override public String[] roles() { return new String[0]; }
                        @Override public String[] anyRole() { return new String[0]; }
                        @Override public Class<? extends Exception> exception() { return AuthException.class; }
                        @Override public Class<? extends Annotation> annotationType() { return RequiresToken.class; }
                    }, args);
                    case "annotationType" -> RequiresToken.class;
                    default -> null;
                });
    }
}
