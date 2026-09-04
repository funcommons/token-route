package fun.commons.tokenroute.config;

/**
 * 路由策略类型（01_设计方案 §4.1）。
 */
public enum TrStrategyType {
    /** 加权随机（负权重按 0 = 出局，02 §8） */
    WEIGHTED_RANDOM,
    /** 顺序轮询（order_no + Redis 游标 tr:rr:{tid}） */
    ROUND_ROBIN,
    /** 权重优先（有效权重 = weight × 降级系数；weight 允许 float/负数——负数即价格优先变形） */
    WEIGHT_FIRST,
    /** 脚本选择器（Groovy 集合级脚本，05 号；必须配置 selector_script） */
    SCRIPT
}
