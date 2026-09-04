package fun.commons.tokenroute.resolve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时条目视图（EntryJSON，03 §2.1）：{entry_id, name, weight, order_no, data_json(含 capacity), status, frozen_until}。
 * 半透明原则：仅 data_json.capacity 进内核，其余载荷不透明（原样返回）。
 */
public class TrEntry {

    public static final String ACTIVE = "ACTIVE";
    public static final String DEGRADED_L1 = "DEGRADED_L1";
    public static final String FROZEN = "FROZEN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String entryId;
    private String name;
    private double weight = 100.0;
    private int orderNo;
    private Map<String, Object> dataJson = new LinkedHashMap<>();
    private String status = ACTIVE;
    private Long frozenUntil;

    public static TrEntry fromJson(String json) {
        try {
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<>() {
            });
            TrEntry e = new TrEntry();
            e.entryId = str(m.get("entry_id"));
            e.name = str(m.get("name"));
            Object w = m.get("weight");
            e.weight = w instanceof Number n ? n.doubleValue() : 100.0;
            Object o = m.get("order_no");
            e.orderNo = o instanceof Number n ? n.intValue() : 0;
            e.status = str(m.getOrDefault("status", ACTIVE));
            e.frozenUntil = m.get("frozen_until") instanceof Number n ? n.longValue() : null;
            Object data = m.get("data_json");
            if (data instanceof Map<?, ?> dm) {
                for (Map.Entry<?, ?> en : dm.entrySet()) {
                    e.dataJson.put(String.valueOf(en.getKey()), en.getValue());
                }
            }
            return e;
        } catch (Exception e) {
            return null; // 坏 EntryJSON 视同无此条目（FEED 校验在拉取侧兜底）
        }
    }

    public TrCapacity capacity() {
        Object c = dataJson.get("capacity");
        return TrCapacity.from(c instanceof Map<?, ?> cm ? (Map<String, Object>) cm : null);
    }

    /**
     * 态域参与判定（01 §4.2）：{ACTIVE, DEGRADED_L1} 参与；
     * FROZEN 且 frozen_until < now → 窗满惰性回 ACTIVE（读取时判定）。
     */
    public boolean selectable(long now) {
        if (ACTIVE.equals(status) || DEGRADED_L1.equals(status)) {
            return true;
        }
        return FROZEN.equals(status) && frozenUntil != null && frozenUntil < now;
    }

    /** 有效权重 = weight × 降级系数（01 §4.2：L1 ×0.5） */
    public double effectiveWeight() {
        return DEGRADED_L1.equals(status) ? weight * 0.5 : weight;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    public String getEntryId() {
        return entryId;
    }

    public String getName() {
        return name;
    }

    public double getWeight() {
        return weight;
    }

    public int getOrderNo() {
        return orderNo;
    }

    public Map<String, Object> getDataJson() {
        return dataJson;
    }

    public String getStatus() {
        return status;
    }

    public Long getFrozenUntil() {
        return frozenUntil;
    }
}
