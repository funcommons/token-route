package fun.commons.tokenroute.resolve;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * TR-CTR-001 取路由请求（02_接口契约 §6）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TrResolveRequest {

    @NotBlank(message = "table_id 必填")
    private String tableId;

    /** 亲和键；表未开亲和时忽略；≤128 字符 */
    private String sessionId;

    /** 供 filter_script / selector_script 使用；≤64 键，值 string/number */
    private Map<String, Object> bizParams;

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

    public Map<String, Object> getBizParams() {
        return bizParams;
    }

    public void setBizParams(Map<String, Object> bizParams) {
        this.bizParams = bizParams;
    }
}
