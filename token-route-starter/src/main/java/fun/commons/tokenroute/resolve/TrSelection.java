package fun.commons.tokenroute.resolve;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内置三策略 + 脚本回退（01 §5.1 第 4 步）。纯函数，Redis 游标由调用方传入。
 * <ul>
 *   <li>加权随机：weight ≤0 条目出局（02 §8：负数仅对 WEIGHT_FIRST/SCRIPT 有意义）</li>
 *   <li>顺序轮询：order_no 排序 + INCR 游标取模（游标由调用方读）</li>
 *   <li>权重优先：有效权重 = weight × 降级系数，允许 float/负数（价格优先变形）；平局取 order_no 小者</li>
 * </ul>
 */
public final class TrSelection {

    private TrSelection() {
    }

    public static TrEntry weightedRandom(List<TrEntry> candidates) {
        List<TrEntry> positive = candidates.stream().filter(e -> e.getWeight() > 0).toList();
        if (positive.isEmpty()) {
            return null;
        }
        double total = positive.stream().mapToDouble(TrEntry::getWeight).sum();
        double pick = ThreadLocalRandom.current().nextDouble(total);
        double acc = 0;
        for (TrEntry e : positive) {
            acc += e.getWeight();
            if (pick < acc) {
                return e;
            }
        }
        return positive.get(positive.size() - 1);
    }

    /** 游标取模（游标 = Redis INCR tr:rr:{tid}，多实例一致）；候选按 order_no 升序 */
    public static TrEntry roundRobin(List<TrEntry> candidates, long cursor) {
        if (candidates.isEmpty()) {
            return null;
        }
        List<TrEntry> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(TrEntry::getOrderNo))
                .toList();
        long idx = (cursor - 1) % sorted.size();
        return sorted.get((int) idx);
    }

    public static TrEntry weightFirst(List<TrEntry> candidates) {
        return candidates.stream()
                .max(Comparator
                        .comparingDouble(TrEntry::effectiveWeight)
                        .thenComparing(Comparator.comparingInt(TrEntry::getOrderNo).reversed()))
                .orElse(null);
    }
}
