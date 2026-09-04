package fun.commons.tokenroute.web;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.tokenroute.admin.TrAffinityAdminService;
import fun.commons.tokenroute.admin.TrAffinityDeleteRequest;
import fun.commons.tokenroute.admin.TrAffinitySetRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 亲和管理端点（issue #1，TR-ADM-001~004）：对既有亲和键的内部运维写面——
 * 「零管理写面」原则的唯一例外；内部鉴权 X-Internal-Service-Token + trusted-ips
 * （TrAffinityAdminInterceptor，02 §3.3），审计日志见 [TR-AFFINITY-ADMIN]。
 */
@RestController
public class TrAffinityAdminController {

    private final TrAffinityAdminService admin;

    public TrAffinityAdminController(TrAffinityAdminService admin) {
        this.admin = admin;
    }

    /** TR-ADM-001：upsert 亲和指向（改流：下一 resolve 亲和命中新指向） */
    @PostMapping("/v1/admin/affinity/set")
    public ApiResponse<Map<String, Object>> set(@Valid @RequestBody TrAffinitySetRequest body,
                                                HttpServletRequest request) {
        validateTtl(body.getTtlSeconds());
        return ApiResponse.success(admin.set(body.getTableId(), body.getSessionId(),
                body.getEntryId(), body.getTtlSeconds(), request.getRemoteAddr()));
    }

    /** TR-ADM-002：查当前亲和指向（含剩余 TTL） */
    @GetMapping("/v1/admin/affinity/get")
    public ApiResponse<Map<String, Object>> get(@RequestParam("table_id") String tableId,
                                                @RequestParam("session_id") String sessionId) {
        return ApiResponse.success(admin.get(tableId, sessionId));
    }

    /** TR-ADM-003：session_id 前缀 SCAN（运维低频排查；limit ≤500，超限 complete=false） */
    @GetMapping("/v1/admin/affinity/list")
    public ApiResponse<Map<String, Object>> list(@RequestParam("table_id") String tableId,
                                                 @RequestParam(value = "session_id_prefix", required = false) String sessionIdPrefix,
                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(admin.list(tableId, sessionIdPrefix, limit));
    }

    /** TR-ADM-004：解除亲和（幂等，无绑定返回 deleted=false） */
    @PostMapping("/v1/admin/affinity/delete")
    public ApiResponse<Map<String, Object>> delete(@Valid @RequestBody TrAffinityDeleteRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.success(admin.delete(body.getTableId(), body.getSessionId(),
                request.getRemoteAddr()));
    }

    private void validateTtl(Integer ttlSeconds) {
        if (ttlSeconds != null && (ttlSeconds < TrAffinityAdminService.TTL_MIN_SECONDS
                || ttlSeconds > TrAffinityAdminService.TTL_MAX_SECONDS)) {
            throw new ApiException(ApiCode.PARAM_ERROR,
                    "ttl_seconds 须在 " + TrAffinityAdminService.TTL_MIN_SECONDS
                            + "~" + TrAffinityAdminService.TTL_MAX_SECONDS + " 秒之间");
        }
    }
}
