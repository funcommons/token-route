package fun.commons.tokenroute.resolve;

/**
 * FEED 回源钩子（01 §5.1 三层取数）：键不存在 → 单飞同步回源一次（超时 3s，防击穿）。
 * S2 仅定义接口 + NOOP 兜底；S4 TrFeedRefreshService 提供真实实现（refresh_url 拉取 + entry_upsert）。
 */
public interface TrFeedBackfill {

    /**
     * 同步拉取该表全量条目并落 Redis；成功 true。
     * 实现须自带 3s 超时与失败保旧值语义。
     */
    boolean pull(String tableId);

    /**
     * 惰性触发（01 §5.5 触发①）：条目列表龄 > refresh-interval → 后台单飞拉取，调用方立即返回旧值。
     * 默认 NOOP（S2/S3 阶段无 FEED 实现）。
     */
    default boolean pullIfStale(String tableId, long refreshIntervalSeconds) {
        return false;
    }

    /** NOOP：S2/S3 阶段冷启动直接 TABLE_EMPTY */
    class Noop implements TrFeedBackfill {
        @Override
        public boolean pull(String tableId) {
            return false;
        }
    }
}
