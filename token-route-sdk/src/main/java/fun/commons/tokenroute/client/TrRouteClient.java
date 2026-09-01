package fun.commons.tokenroute.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * token-route 契约面 HTTP 客户端（02_接口契约 §6；结构/信封解析对齐 ThmpContractClient 平移）。
 * <ul>
 *   <li>模式头：apikey → X-Api-Key；jwt → Authorization: Bearer（token 由调用方提供 supplier，只透传不管理）；none 无头</li>
 *   <li>lease_id 透传纪律：resolve → 上游调用 → report（leaseId 必须原样回传）</li>
 *   <li><b>无本地缓存</b>（预占直连模式，01 §7.2）——每次调用直连决议</li>
 * </ul>
 */
public class TrRouteClient {

    /** 统一信封（02 §2：6 字段；error 空省略） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Envelope<T> {
        private int code;
        private String message;
        private T data;
        private String traceId;
        private long timestamp;

        public boolean isOk() {
            return code == 0;
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public T getData() {
            return data;
        }

        public String getTraceId() {
            return traceId;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    /** resolve 响应 data（核心三元组 + affinity + reasons） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ResolveResult {
        private String entryId;
        private Map<String, Object> dataJson;
        private String leaseId;
        private String affinity;
        private List<String> reasons = List.of();

        public String getEntryId() {
            return entryId;
        }

        public Map<String, Object> getDataJson() {
            return dataJson;
        }

        public String getLeaseId() {
            return leaseId;
        }

        public String getAffinity() {
            return affinity;
        }

        public List<String> getReasons() {
            return reasons;
        }
    }

    /** report 拒收明细（10700 只补发 rejected） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class RejectedItem {
        private int index;
        private int code;
        private String message;

        public int getIndex() {
            return index;
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

    /** report 响应 data：{accepted, rejected[]} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ReportResult {
        private int accepted;
        private List<RejectedItem> rejected = List.of();

        public int getAccepted() {
            return accepted;
        }

        public List<RejectedItem> getRejected() {
            return rejected;
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final Supplier<String> bearerToken;
    private final String callerId;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private TrRouteClient(String baseUrl, String apiKey, Supplier<String> bearerToken, String callerId) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.bearerToken = bearerToken;
        this.callerId = callerId;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** none 模式（内网信任） */
    public static TrRouteClient open(String baseUrl) {
        return new TrRouteClient(baseUrl, null, null, null);
    }

    /** apikey 模式（静态共享密钥） */
    public static TrRouteClient apiKey(String baseUrl, String apiKey) {
        return new TrRouteClient(baseUrl, apiKey, null, null);
    }

    /** jwt 模式（token 由平台统一认证签发，客户端只透传） */
    public static TrRouteClient jwt(String baseUrl, Supplier<String> bearerToken) {
        return new TrRouteClient(baseUrl, null, bearerToken, null);
    }

    public TrRouteClient callerId(String callerId) {
        return new TrRouteClient(baseUrl, apiKey, bearerToken, callerId);
    }

    /** TR-CTR-001 resolve：EMPTY（entry_id=null）不抛错，由调用方按 reasons 处理 */
    public Envelope<ResolveResult> resolve(String tableId, String sessionId, Map<String, Object> bizParams)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("table_id", tableId);
        if (sessionId != null) {
            body.put("session_id", sessionId);
        }
        if (bizParams != null) {
            body.put("biz_params", bizParams);
        }
        return post("/v1/resolve", body, ResolveResult.class);
    }

    /** TR-CTR-002 report：返回 rejected 明细（空 = 全部受理；10700 不抛错） */
    public Envelope<ReportResult> report(List<Map<String, Object>> reports) throws Exception {
        return post("/v1/report", Map.of("reports", reports), ReportResult.class);
    }

    private <T> Envelope<T> post(String path, Object body, JavaType dataType) throws Exception {
        JavaType envelopeType = mapper.getTypeFactory().constructParametricType(Envelope.class, dataType);
        return doPost(path, body, envelopeType);
    }

    /** TR-CTR-003 detach（幂等） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Envelope<Map<String, Object>> detach(String tableId, String sessionId) throws Exception {
        return (Envelope) post("/v1/affinity/detach", Map.of("table_id", tableId, "session_id", sessionId),
                Map.class);
    }

    /** TR-CTR-004 立即刷新 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Envelope<Map<String, Object>> refresh(String tableId) throws Exception {
        return (Envelope) post("/v1/refresh/" + tableId, Map.of(), Map.class);
    }

    private <T> Envelope<T> post(String path, Object body, Class<T> dataType) throws Exception {
        JavaType envelopeType = mapper.getTypeFactory()
                .constructParametricType(Envelope.class, dataType);
        return doPost(path, body, envelopeType);
    }

    private <T> Envelope<T> doPost(String path, Object body, JavaType envelopeType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (apiKey != null) {
            builder.header("X-Api-Key", apiKey);
        }
        if (bearerToken != null) {
            String token = bearerToken.get();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        if (callerId != null) {
            builder.header("X-Caller-Id", callerId);
        }
        HttpResponse<String> response = http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), envelopeType);
    }

    private JavaType listOf(Class<?> element) {
        return mapper.getTypeFactory().constructCollectionType(List.class, element);
    }
}
