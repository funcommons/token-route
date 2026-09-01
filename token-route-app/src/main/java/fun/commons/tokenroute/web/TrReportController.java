package fun.commons.tokenroute.web;

import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.tokenroute.report.TrReportRequest;
import fun.commons.tokenroute.report.TrReportResponse;
import fun.commons.tokenroute.report.TrReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * TR-CTR-002 结果回填 report（02_接口契约 §6）：
 * 全部受理 → code 0；存在拒收 → 10700，data.rejected[] 携带 {index, code, message}。
 */
@RestController
public class TrReportController {

    private final TrReportService reportService;

    public TrReportController(TrReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/v1/report")
    public ApiResponse<TrReportResponse> report(@Valid @RequestBody TrReportRequest request) {
        TrReportResponse data = reportService.report(request);
        if (data.getRejected().isEmpty()) {
            return ApiResponse.success(data);
        }
        List<ApiError> errors = data.getRejected().stream()
                .map(r -> new ApiError(String.valueOf(r.getIndex()), String.valueOf(r.getCode()),
                        r.getMessage(), null))
                .toList();
        return ApiResponse.partialSuccess(data, errors);
    }
}
