package fun.commons.tokenroute.web;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.tokenroute.TrRouteEngine;
import fun.commons.tokenroute.config.TrTableRegistry;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * TR-CTR-003 亲和解除（02_接口契约 §6）：消费方会话终态清理——幂等，
 * 无绑定返回 detached=false；内核实现在 TrRouteEngine.detach（DETACH 亲和事件 ring 可查）。
 */
@RestController
public class TrDetachController {

    private final TrRouteEngine engine;
    private final TrTableRegistry registry;

    public TrDetachController(TrRouteEngine engine, TrTableRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    @PostMapping("/v1/affinity/detach")
    public ApiResponse<Map<String, Object>> detach(@Valid @RequestBody TrDetachRequest body) {
        registry.find(body.getTableId())
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND,
                        "table_id 未注册: " + body.getTableId()));
        if (body.getSessionId().length() > 128) {
            throw new ApiException(ApiCode.PARAM_ERROR, "session_id 超长（≤128 字符）");
        }
        return ApiResponse.success(Map.of("detached", engine.detach(body.getTableId(), body.getSessionId())));
    }
}
