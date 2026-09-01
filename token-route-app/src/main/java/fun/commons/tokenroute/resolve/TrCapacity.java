package fun.commons.tokenroute.resolve;

import java.util.Map;

/**
 * capacity 约定字段（02 §8.1：内核唯一读取的载荷字段；整体缺省 = 不启用容量治理）。
 */
public record TrCapacity(Integer maxConcurrency, Double rateLimitValue, Long rateWindowMs, TrRateUnit rateUnit) {

    public static TrCapacity from(Map<String, Object> capacity) {
        if (capacity == null || capacity.isEmpty()) {
            return null;
        }
        Integer maxConc = asInt(capacity.get("max_concurrency"));
        Double limit = asDouble(capacity.get("rate_limit_value"));
        Long window = asLong(capacity.get("rate_window_ms"));
        TrRateUnit unit = TrRateUnit.REQUEST;
        Object u = capacity.get("rate_unit");
        if (u != null) {
            try {
                unit = TrRateUnit.valueOf(String.valueOf(u).toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 非法口径回落 REQUEST（FEED 校验在 S4 拒收 10633，运行期宽容）
            }
        }
        return new TrCapacity(maxConc, limit, window, unit);
    }

    public Long rateWindowOrDefault() {
        return rateWindowMs != null ? rateWindowMs : 1000L;
    }

    public boolean governed() {
        return maxConcurrency != null || rateLimitValue != null;
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }
}
