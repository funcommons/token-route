package fun.commons.tokenroute.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/**
 * TR-ADM-004 亲和管理 delete 请求（02_接口契约 §7.1）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TrAffinityDeleteRequest {

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
