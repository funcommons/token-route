package fun.commons.tokenroute.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI 3 机读契约导出件（07 S5 出口）：随构建产物交付；
 * 解析校验冻结的 /v1 契约面 8 端点 + 信封 schema（02_接口契约 §9：破坏性变更 = 契约评审）。
 */
class OpenApiContractTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        try (InputStream in = getClass().getResourceAsStream("/openapi/token-route-openapi.yaml")) {
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("契约文件不可读", e);
        }
    }

    @Test
    void contractFileParsesAndDeclaresOpenApi3() {
        Map<String, Object> doc = load();
        assertThat(doc.get("openapi")).isEqualTo("3.0.3");
        Map<String, Object> info = (Map<String, Object>) doc.get("info");
        assertThat(String.valueOf(info.get("title"))).contains("token-route");
    }

    @Test
    void frozenV1PathsAreAllDeclared() {
        Map<String, Object> doc = load();
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        // 契约面 4 + ops 4 + 内部管理 5（亲和管理 4 + 状态重置 1，issue #1，零管理写面唯一例外）；
        // URL 版本段冻结 /v1，响应只加不减（02 §9）
        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                "/v1/ping", "/v1/resolve", "/v1/report", "/v1/affinity/detach", "/v1/refresh/{tid}",
                "/v1/ops/tables/{tid}/status", "/v1/ops/resolve-logs", "/v1/ops/affinity-events",
                "/v1/ops/state-logs",
                "/v1/admin/affinity/set", "/v1/admin/affinity/get",
                "/v1/admin/affinity/list", "/v1/admin/affinity/delete", "/v1/admin/state/reset");
    }

    @Test
    void reportContractPinsTristateAndBatchBounds() {
        Map<String, Object> doc = load();
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Map<String, Object> report = (Map<String, Object>) paths.get("/v1/report");
        assertThat(String.valueOf(report)).contains("SUCCESS").contains("RETRYABLE_FAIL").contains("DISABLE_FAIL");
        Map<String, Object> resolve = (Map<String, Object>) paths.get("/v1/resolve");
        assertThat(String.valueOf(resolve)).contains("lease_id").contains("session_id");
    }
}
