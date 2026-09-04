package fun.commons.tokenroute.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/**
 * TR-ADM-005 状态重置请求（02_接口契约 §7.1）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TrStateResetRequest {

    @NotBlank(message = "table_id 必填")
    private String tableId;

    @NotBlank(message = "entry_id 必填")
    private String entryId;

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String entryId) {
        this.entryId = entryId;
    }
}
