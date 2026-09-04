package fun.commons.tokenroute.auth;

import fun.commons.tokenroute.common.TrCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 亲和管理端点内部鉴权（02_接口契约 §3.3，issue #1）：X-Internal-Service-Token 恒时比对 + 可信 IP 白名单，
 * 与 MMagiX 内部服务鉴权同模式——不走契约面/ops 面三模式（独立拦截器，TrConfig 注册时互斥）。
 * <ul>
 *   <li>令牌未配置（空）→ 端点整体禁用，一律 HTTP 401（安全缺省，防裸奔上线）</li>
 *   <li>令牌缺失/不符 → HTTP 401 + 信封（10200）</li>
 *   <li>trusted-ips 非空且来源不在名单 → HTTP 403 + 信封</li>
 * </ul>
 */
public class TrAffinityAdminInterceptor implements HandlerInterceptor {

    /** 内部服务令牌头（02 §3.3） */
    public static final String TOKEN_HEADER = "X-Internal-Service-Token";

    private final TrAuthProperties auth;

    public TrAffinityAdminInterceptor(TrAuthProperties auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        TrAuthProperties.AffinityAdmin cfg = auth.getAffinityAdmin();
        String expected = cfg.getInternalToken();
        if (expected == null || expected.isBlank()) {
            return reject(response, HttpStatus.UNAUTHORIZED, "亲和管理端点未配置内部令牌（已禁用）");
        }
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return reject(response, HttpStatus.UNAUTHORIZED, "内部服务令牌缺失或不符");
        }
        if (!cfg.getTrustedIps().isEmpty() && !cfg.getTrustedIps().contains(request.getRemoteAddr())) {
            return reject(response, HttpStatus.FORBIDDEN, "来源 IP 不在可信名单: " + request.getRemoteAddr());
        }
        return true;
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, String message) throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"code\":" + TrCode.UNAUTHORIZED.getCode()
                        + ",\"message\":\"" + message
                        + "\",\"data\":null,\"error\":[],\"trace_id\":\"\",\"timestamp\":"
                        + System.currentTimeMillis() + "}");
        return false;
    }
}
