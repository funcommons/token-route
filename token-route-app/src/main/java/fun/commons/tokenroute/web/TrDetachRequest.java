package fun.commons.tokenroute.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/**
 * TR-CTR-003 亲和解除请求（02_接口契约 §6）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TrDetachRequest {

    @NotBlank(message = "table_id 必填")
    private String tableId;

    @NotBlank(message = "session_id 必填")
    private String sessionId;

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
}
