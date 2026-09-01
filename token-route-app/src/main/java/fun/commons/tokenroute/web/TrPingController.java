package fun.commons.tokenroute.web;

import fun.commons.framework4j.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 契约面 ping（TR 探活；鉴权随契约面模式）。
 */
@RestController
public class TrPingController {

    @GetMapping("/v1/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.success(Map.of(
                "service", "token-route",
                "face", "contract",
                "ts", OffsetDateTime.now().toString()));
    }
}
