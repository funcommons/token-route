package fun.commons.tokenroute.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * TR-CTR-002 结果回填请求（02_接口契约 §6）：批量 1~100 条，越界 10100。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TrReportRequest {

    public static final int MAX_BATCH = 100;

    private List<Item> reports;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Item {
        private String entryId;
        private String leaseId;
        /** SUCCESS / RETRYABLE_FAIL / DISABLE_FAIL */
        private String result;
        /** 默认 1；REQUEST 桶恒按次，TOKEN/BIT 桶传实际量；0 = 调用前拒绝 */
        private Double rateUnits = 1.0;
        private Integer latencyMs;
        /** DISABLE_FAIL 时即时脱离该会话 */
        private String sessionId;

        public String getEntryId() {
            return entryId;
        }

        public void setEntryId(String entryId) {
            this.entryId = entryId;
        }

        public String getLeaseId() {
            return leaseId;
        }

        public void setLeaseId(String leaseId) {
            this.leaseId = leaseId;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public Double getRateUnits() {
            return rateUnits;
        }

        public void setRateUnits(Double rateUnits) {
            this.rateUnits = rateUnits;
        }

        public Integer getLatencyMs() {
            return latencyMs;
        }

        public void setLatencyMs(Integer latencyMs) {
            this.latencyMs = latencyMs;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    public List<Item> getReports() {
        return reports;
    }

    public void setReports(List<Item> reports) {
        this.reports = reports;
    }
}
