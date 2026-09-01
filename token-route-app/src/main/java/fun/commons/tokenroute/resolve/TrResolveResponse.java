package fun.commons.tokenroute.resolve;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * TR-CTR-001 响应（02_接口契约 §6）：{entry_id, data_json, lease_id, affinity, reasons}。
 * EMPTY 语义：entry_id=null、lease_id=null、HTTP 200 code 0 + reasons，不抛错不阻塞。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TrResolveResponse {

    /** 空结果原因码（02 §6）：ALL_FILTERED / TABLE_EMPTY / TABLE_OFFLINE / CAPACITY_EXHAUSTED / SCRIPT_DEGRADED */
    public static final String R_ALL_FILTERED = "ALL_FILTERED";
    public static final String R_TABLE_EMPTY = "TABLE_EMPTY";
    public static final String R_TABLE_OFFLINE = "TABLE_OFFLINE";
    public static final String R_CAPACITY = "CAPACITY_EXHAUSTED";
    public static final String R_SCRIPT_DEGRADED = "SCRIPT_DEGRADED";

    /** 亲和语义（02 §6）：HIT / NEW / NONE */
    public static final String AFF_HIT = "HIT";
    public static final String AFF_NEW = "NEW";
    public static final String AFF_NONE = "NONE";

    private String entryId;
    private Map<String, Object> dataJson;
    private String leaseId;
    private String affinity;
    private List<String> reasons = List.of();

    public static TrResolveResponse empty(List<String> reasons) {
        TrResolveResponse r = new TrResolveResponse();
        r.reasons = reasons;
        r.affinity = AFF_NONE;
        return r;
    }

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String entryId) {
        this.entryId = entryId;
    }

    public Map<String, Object> getDataJson() {
        return dataJson;
    }

    public void setDataJson(Map<String, Object> dataJson) {
        this.dataJson = dataJson;
    }

    public String getLeaseId() {
        return leaseId;
    }

    public void setLeaseId(String leaseId) {
        this.leaseId = leaseId;
    }

    public String getAffinity() {
        return affinity;
    }

    public void setAffinity(String affinity) {
        this.affinity = affinity;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons;
    }
}
