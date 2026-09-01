package fun.commons.tokenroute.web;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.tokenroute.resolve.TrResolveRequest;
import fun.commons.tokenroute.resolve.TrResolveResponse;
import fun.commons.tokenroute.resolve.TrResolveService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * TR-CTR-001 取路由 resolve（02_接口契约 §6）。
 */
@RestController
public class TrResolveController {

    /** 调用方归因（02 §3.1）：X-Caller-Id 缺省/携带均不拦截，仅落决议日志 */
    static final String CALLER_HEADER = "X-Caller-Id";

    private final TrResolveService resolveService;

    public TrResolveController(TrResolveService resolveService) {
        this.resolveService = resolveService;
    }

    @PostMapping("/v1/resolve")
    public ApiResponse<TrResolveResponse> resolve(@Valid @RequestBody TrResolveRequest request,
                                                  @RequestHeader(value = CALLER_HEADER, required = false) String callerId) {
        validate(request);
        return ApiResponse.success(resolveService.resolve(request, callerId));
    }

    private void validate(TrResolveRequest request) {
        if (request.getSessionId() != null && request.getSessionId().length() > 128) {
            throw new ApiException(ApiCode.PARAM_ERROR, "session_id 超长（≤128 字符）");
        }
        if (request.getBizParams() != null && request.getBizParams().size() > 64) {
            throw new ApiException(ApiCode.PARAM_ERROR, "biz_params 超过 64 键");
        }
        for (Object v : (request.getBizParams() == null ? Map.<String, Object>of() : request.getBizParams()).values()) {            if (!(v instanceof String || v instanceof Number || v == null)) {
                throw new ApiException(ApiCode.PARAM_ERROR, "biz_params 值仅允许 string/number");
            }
        }
    }
}
