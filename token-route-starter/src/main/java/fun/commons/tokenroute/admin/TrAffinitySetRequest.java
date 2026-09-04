package fun.commons.tokenroute.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/**
 * TR-ADM-001 亲和管理 set 请求（02_接口契约 §7.1）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TrAffinitySetRequest {

    @NotBlank(message = "table_id 必填")
    private String tableId;

    @NotBlank(message = "session_id 必填")
    private String sessionId;

    @NotBlank(message = "entry_id 必填")
    private String entryId;

    /** 缺省沿用表级 affinity_idle_timeout_seconds；范围 1~604800（7d） */
    private Integer ttlSeconds;

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String entryId) {
        this.entryId = entryId;
    }

    public Integer getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Integer ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
