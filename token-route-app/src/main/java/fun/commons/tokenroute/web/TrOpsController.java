package fun.commons.tokenroute.web;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.tokenroute.ops.TrOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运维只读面（02_接口契约 §7，TR-OPS-001~004；鉴权随 ops 面模式，默认 jwt/tr-admin）。
 */
@RestController
public class TrOpsController {

    private final TrOpsService ops;

    public TrOpsController(TrOpsService ops) {
        this.ops = ops;
    }

    /** TR-OPS-001：表 + 条目实时状态 */
    @GetMapping("/v1/ops/tables/{tid}/status")
    public ApiResponse<Map<String, Object>> tableStatus(@PathVariable String tid) {
        try {
            return ApiResponse.success(ops.tableStatus(tid));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ApiCode.NOT_FOUND, e.getMessage());
        }
    }

    /** TR-OPS-002：决议日志（ring 热窗 7d） */
    @GetMapping("/v1/ops/resolve-logs")
    public ApiResponse<List<Object>> resolveLogs(
            @RequestParam("table_id") String tableId,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(ops.resolveLogs(tableId, from, to, result, page, Math.min(size, 1000)));
    }

    /** TR-OPS-003：亲和事件史（BIND|DETACH） */
    @GetMapping("/v1/ops/affinity-events")
    public ApiResponse<List<Object>> affinityEvents(
            @RequestParam("table_id") String tableId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(ops.affinityEvents(tableId, type, page, Math.min(size, 1000)));
    }

    /** TR-OPS-004：状态迁移史 */
    @GetMapping("/v1/ops/state-logs")
    public ApiResponse<List<Object>> stateLogs(
            @RequestParam("entry_id") String entryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(ops.stateLogs(entryId, page, Math.min(size, 1000)));
    }
}
