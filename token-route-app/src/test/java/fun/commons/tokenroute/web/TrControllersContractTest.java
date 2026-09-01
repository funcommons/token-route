package fun.commons.tokenroute.web;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.tokenroute.common.TrCode;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.feed.TrFeedRefreshService;
import fun.commons.tokenroute.report.TrReportRequest;
import fun.commons.tokenroute.report.TrReportResponse;
import fun.commons.tokenroute.report.TrReportService;
import fun.commons.tokenroute.resolve.TrResolveRequest;
import fun.commons.tokenroute.resolve.TrResolveResponse;
import fun.commons.tokenroute.resolve.TrResolveService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制器分支直调（02 §6）：resolve 请求校验三拒 + 放行、X-Caller-Id 透传、
 * refresh 未注册 10400 与成败回显、report 全受理 code 0 与部分受理 10700 + error 明细。
 */
class TrControllersContractTest {

    // —— resolve ——

    @Test
    void resolveValidatesSessionIdLength() {
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId("t1");
        r.setSessionId("s".repeat(129));
        ApiException ex = catchThrowableOfType(
                () -> new TrResolveController(mock(TrResolveService.class)).resolve(r, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(TrCode.PARAM_ERROR.getCode());
    }

    @Test
    void resolveValidatesBizParamsSizeAndValueTypes() {
        TrResolveController controller = new TrResolveController(mock(TrResolveService.class));

        Map<String, Object> tooMany = new HashMap<>();
        for (int i = 0; i < 65; i++) {
            tooMany.put("k" + i, i);
        }
        TrResolveRequest big = new TrResolveRequest();
        big.setTableId("t1");
        big.setBizParams(tooMany);
        assertThat(catchThrowableOfType(() -> controller.resolve(big, null), ApiException.class))
                .isNotNull();

        TrResolveRequest bad = new TrResolveRequest();
        bad.setTableId("t1");
        bad.setBizParams(Map.of("obj", List.of("not-scalar")));
        assertThat(catchThrowableOfType(() -> controller.resolve(bad, null), ApiException.class))
                .isNotNull();
    }

    @Test
    void resolvePassesValidationAndCallerIdThrough() {
        TrResolveService svc = mock(TrResolveService.class);
        TrResolveController controller = new TrResolveController(svc);

        Map<String, Object> params = new HashMap<>();
        params.put("str", "v");
        params.put("num", 3);
        params.put("none", null); // null 值放行
        TrResolveRequest r = new TrResolveRequest();
        r.setTableId("t1");
        r.setSessionId("s1");
        r.setBizParams(params);
        TrResolveResponse data = new TrResolveResponse();
        data.setEntryId("e-1");
        when(svc.resolve(r, "caller-1")).thenReturn(data);

        ApiResponse<TrResolveResponse> resp = controller.resolve(r, "caller-1");
        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData().getEntryId()).isEqualTo("e-1");
        verify(svc).resolve(same(r), eq("caller-1"));

        controller.resolve(r, null); // 缺省 caller 头
        verify(svc).resolve(same(r), eq(null));
    }

    // —— refresh ——

    @Test
    void refreshUnknownTableIsNotFound() {
        TrTableDefinition d = new TrTableDefinition();
        d.setName("t-good");
        d.setRefreshUrl("http://feed.example/t-good");
        TrRefreshController controller = new TrRefreshController(
                mock(TrFeedRefreshService.class), TrTableRegistry.load(List.of(d)));
        ApiException ex = catchThrowableOfType(() -> controller.refresh("ghost"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiCode.NOT_FOUND.getCode());
    }

    @Test
    void refreshEchoesResultAndError() {
        TrFeedRefreshService feed = mock(TrFeedRefreshService.class);
        TrTableDefinition d = new TrTableDefinition();
        d.setName("t-good");
        d.setRefreshUrl("http://feed.example/t-good");
        TrRefreshController controller = new TrRefreshController(feed,
                TrTableRegistry.load(List.of(d)));

        when(feed.pullNow("t-good")).thenReturn(new TrFeedRefreshService.Result(true, 2, 2, 1, 5, null));
        ApiResponse<Map<String, Object>> ok = controller.refresh("t-good");
        assertThat(ok.getCode()).isZero();
        assertThat(ok.getData()).containsEntry("pulled", 2)
                .containsEntry("upserted", 2).containsEntry("removed", 1);
        assertThat(ok.getData()).doesNotContainKey("error");

        when(feed.pullNow("t-good")).thenReturn(new TrFeedRefreshService.Result(false, 0, 0, 0, 3, "upstream down"));
        ApiResponse<Map<String, Object>> failed = controller.refresh("t-good");
        assertThat(failed.getCode()).isZero(); // 失败保旧值仍 200/code 0
        assertThat(failed.getData()).containsEntry("error", "upstream down");
    }

    // —— report ——

    @Test
    void reportAllAcceptedIsSuccess() {
        TrReportService svc = mock(TrReportService.class);
        TrReportResponse data = new TrReportResponse();
        data.setAccepted(2);
        when(svc.report(any(TrReportRequest.class))).thenReturn(data);
        ApiResponse<TrReportResponse> resp = new TrReportController(svc).report(anyRequest());
        assertThat(resp.getCode()).isZero();
        assertThat(resp.getError()).isNull();
    }

    @Test
    void reportPartialSuccessCarriesRejectedDetails() {
        TrReportService svc = mock(TrReportService.class);
        TrReportResponse data = new TrReportResponse();
        data.setAccepted(1);
        data.getRejected().add(new TrReportResponse.Rejected(1, 10404, "未知 entry_id: x"));
        when(svc.report(any(TrReportRequest.class))).thenReturn(data);
        ApiResponse<TrReportResponse> resp = new TrReportController(svc).report(anyRequest());
        assertThat(resp.isSuccess()).isFalse();
        List<ApiError> errors = resp.getError();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).field()).isEqualTo("1"); // index 落 ApiError.field 位
    }

    private static TrReportRequest anyRequest() {
        TrReportRequest r = new TrReportRequest();
        r.setReports(new ArrayList<>());
        return r;
    }
}
