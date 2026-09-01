package fun.commons.tokenroute.web;

import fun.commons.framework4j.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 运维面 ping（TR 探活；鉴权随 ops 面模式，默认 jwt）。
 */
@RestController
public class TrOpsPingController {

    @GetMapping("/v1/ops/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.success(Map.of(
                "service", "token-route",
                "face", "ops",
                "ts", OffsetDateTime.now().toString()));
    }
}
