package fun.commons.tokenroute.engine;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 脚本 helper 函数（05 §3 上下文绑定，封闭集）。
 * ⚠️ num() 对缺值/非法返回 0——选择器 min/max 场景必须先剔除缺值条目（05 §6 零值陷阱）。
 */
public final class TrScriptFunctions {

    private TrScriptFunctions() {
    }

    /** string→number 安全转换；null/非法 → 0 */
    public static Number num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** string 化安全转换；null → "" */
    public static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** string 长度或集合大小；null → 0 */
    public static int len(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof String s) {
            return s.length();
        }
        if (v instanceof Collection<?> c) {
            return c.size();
        }
        if (v instanceof Map<?, ?> m) {
            return m.size();
        }
        return 0;
    }

    /** v 是否在 coll 内；coll 非法视为不在；null 成员安全比对 */
    public static boolean in(Object v, Object coll) {
        if (coll instanceof Collection<?> c) {
            if (v == null) {
                for (Object o : c) {
                    if (o == null) {
                        return true;
                    }
                }
                return false;
            }
            return c.contains(v);
        }
        if (coll != null) {
            return String.valueOf(coll).equals(v == null ? null : String.valueOf(v));
        }
        return false;
    }

    /** 集合最小值；空/非法 → null */
    public static Object min(Object coll) {
        return extremum(coll, true);
    }

    /** 集合最大值；空/非法 → null */
    public static Object max(Object coll) {
        return extremum(coll, false);
    }

    private static Object extremum(Object coll, boolean min) {
        if (!(coll instanceof Collection<?> c) || c.isEmpty()) {
            return null;
        }
        return c.stream()
                .filter(v -> v instanceof Comparable<?> || v instanceof Number)
                .min((a, b) -> compare(a, b) * (min ? 1 : -1))
                .orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        return ((Comparable) a).compareTo(b);
    }
}
