package fun.commons.tokenroute.web;

import fun.commons.framework4j.web.ApiResponse;
import fun.commons.tokenroute.admin.TrStateAdminService;
import fun.commons.tokenroute.admin.TrStateResetRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 状态重置端点（TR-ADM-005，issue #1 追加）：运营手动把条目从 FROZEN/降级态即时复位 ACTIVE
 * （余额不足停用 → 充值完成后手动立即恢复）。内部鉴权随亲和管理面
 * （X-Internal-Service-Token + trusted-ips，TrAffinityAdminInterceptor 覆盖 /v1/admin/**）；
 * 审计日志见 [TR-STATE-ADMIN] + 状态迁移史（reason=ADMIN_RESET）。
 */
@RestController
public class TrStateAdminController {

    private final TrStateAdminService admin;

    public TrStateAdminController(TrStateAdminService admin) {
        this.admin = admin;
    }

    /** TR-ADM-005：条目状态即时复位 ACTIVE（幂等；OFFLINE 拒绝——FEED 管理位） */
    @PostMapping("/v1/admin/state/reset")
    public ApiResponse<Map<String, Object>> reset(@Valid @RequestBody TrStateResetRequest body,
                                                  HttpServletRequest request) {
        return ApiResponse.success(admin.reset(body.getTableId(), body.getEntryId(),
                request.getRemoteAddr()));
    }
}
