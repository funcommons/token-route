package fun.commons.tokenroute.resolve;

/**
 * 速率记账口径（02 §8.1 capacity.rate_unit）——决定 report rate_units 的计量桶。
 */
public enum TrRateUnit {
    REQUEST,
    TOKEN,
    BIT
}
