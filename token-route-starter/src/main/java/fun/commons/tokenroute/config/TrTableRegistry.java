package fun.commons.tokenroute.config;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 表注册表（config 种子加载产物，进程内存持有）。
 * 启动加载即全量 schema 校验：失败以 10633 指名表/字段拒绝启动（01 §5.6 生效链）。
 * 纯 Java 对象，可脱离 Spring 单测。
 */
public final class TrTableRegistry {

    private final Map<String, TrTableDefinition> tables;

    private TrTableRegistry(Map<String, TrTableDefinition> tables) {
        this.tables = tables;
    }

    public static TrTableRegistry load(List<TrTableDefinition> seed) {
        Map<String, TrTableDefinition> map = new LinkedHashMap<>();
        for (TrTableDefinition t : seed) {
            requireText(t.getName(), t, "name");
            requireText(t.getRefreshUrl(), t, "refresh_url");
            if (map.containsKey(t.getName())) {
                throw new TrSeedConfigException("表名重复: " + t.getName());
            }
            requireText(t.getTenantId(), t, "tenant_id");
            if (t.getStrategyType() == null) {
                throw new TrSeedConfigException("表 " + t.getName() + " strategy_type 非法（允许 WEIGHTED_RANDOM/ROUND_ROBIN/WEIGHT_FIRST/SCRIPT）");
            }
            if (t.getStrategyType() == TrStrategyType.SCRIPT) {
                requireText(t.getSelectorScript(), t, "selector_script");
            }
            requirePositive(t.getLeaseTtlSeconds(), t, "lease_ttl_seconds");
            requirePositive(t.getRefreshIntervalSeconds(), t, "refresh_interval_seconds");
            requirePositive(t.getAffinityIdleTimeoutSeconds(), t, "affinity_idle_timeout_seconds");
            requirePositive(t.getAffinityMaxFailures(), t, "affinity_max_failures");
            if (!"ACTIVE".equals(t.getStatus()) && !"OFFLINE".equals(t.getStatus())) {
                throw new TrSeedConfigException("表 " + t.getName() + " status 仅允许 ACTIVE/OFFLINE");
            }
            map.put(t.getName(), t);
        }
        return new TrTableRegistry(Map.copyOf(map));
    }

    private static void requireText(String v, TrTableDefinition t, String field) {
        if (v == null || v.isBlank()) {
            throw new TrSeedConfigException("表 " + (t.getName() == null ? "<未命名>" : t.getName()) + " 必填字段缺失: " + field);
        }
    }

    private static void requirePositive(int v, TrTableDefinition t, String field) {
        if (v <= 0) {
            throw new TrSeedConfigException("表 " + t.getName() + " 参数须为正整数: " + field);
        }
    }

    public Optional<TrTableDefinition> find(String tableId) {
        return Optional.ofNullable(tables.get(tableId));
    }

    public boolean exists(String tableId) {
        return tables.containsKey(tableId);
    }

    /** 注册且 status=ACTIVE 才在线；OFFLINE 表走 EMPTY + TABLE_OFFLINE（02 §6） */
    public boolean isOnline(String tableId) {
        TrTableDefinition t = tables.get(tableId);
        return t != null && "ACTIVE".equals(t.getStatus());
    }

    public Collection<TrTableDefinition> all() {
        return tables.values();
    }

    public Set<String> tableIds() {
        return tables.keySet();
    }
}
