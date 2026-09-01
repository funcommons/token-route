package fun.commons.tokenroute.web;

import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import fun.commons.tokenroute.config.TrTableRegistry;
import fun.commons.tokenroute.feed.TrFeedRefreshService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * TR-CTR-004 立即刷新（02_接口契约 §6）：同步拉取 refresh_url 并生效。
 * 拉取失败 → 200 + code 0 但 pulled=0 + error 原因（失败保旧值，不抛错）；未注册 → 10400。
 */
@RestController
public class TrRefreshController {

    private final TrFeedRefreshService feed;
    private final TrTableRegistry registry;

    public TrRefreshController(TrFeedRefreshService feed, TrTableRegistry registry) {
        this.feed = feed;
        this.registry = registry;
    }

    @PostMapping("/v1/refresh/{tid}")
    public ApiResponse<Map<String, Object>> refresh(@PathVariable String tid) {
        if (!registry.exists(tid)) {
            throw new ApiException(ApiCode.NOT_FOUND, "table_id 未注册: " + tid);
        }
        TrFeedRefreshService.Result r = feed.pullNow(tid);
        Map<String, Object> data = new HashMap<>();
        data.put("pulled", r.pulled());
        data.put("upserted", r.upserted());
        data.put("removed", r.removed());
        data.put("elapsed_ms", r.elapsedMs());
        if (!r.success()) {
            data.put("error", r.error());
        }
        return ApiResponse.success(data);
    }
}
