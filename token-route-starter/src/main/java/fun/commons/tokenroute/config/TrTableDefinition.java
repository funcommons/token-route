package fun.commons.tokenroute.config;

/**
 * 表定义（config 种子 tr.tables[].，01_设计方案 §4.1）。
 * 真源 = config 文件（进程内存持有，Redis 沉没不影响表壳）；换表/调参 = 改配置 + 滚动重启。
 */
public class TrTableDefinition {

    /** 表名即 table_id（config 声明），必填 */
    private String name;
    /** 归属租户，0=公共 */
    private String tenantId = "0";
    private TrStrategyType strategyType = TrStrategyType.WEIGHTED_RANDOM;
    /** SCRIPT 策略必填：选择器脚本文件路径（file:/conf/tr-scripts/xx.groovy） */
    private String selectorScript;
    /** 可选：条目过滤谓词脚本路径 */
    private String filterScript;
    private boolean affinityEnabled = true;
    /** 亲和空闲超时（默认 8h） */
    private int affinityIdleTimeoutSeconds = 28800;
    /** 绑定条目连败脱离阈值（默认 5） */
    private int affinityMaxFailures = 5;
    /** 并发租约 TTL（默认 30s） */
    private int leaseTtlSeconds = 30;
    /** 状态机判定参数组（V1 仅 default；channel-legacy 预设 P2） */
    private String statePolicy = "default";
    /** 载荷标注 generic / llm-supply / notify-channel */
    private String dataProfile = "generic";
    /** FEED 拉取源（必填，唯一条目数据入口） */
    private String refreshUrl;
    /** FEED 惰性刷新周期（默认 60s） */
    private int refreshIntervalSeconds = 60;
    /** 表状态 ACTIVE / OFFLINE（OFFLINE 注册但 resolve 走 EMPTY + TABLE_OFFLINE） */
    private String status = "ACTIVE";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public TrStrategyType getStrategyType() {
        return strategyType;
    }

    public void setStrategyType(TrStrategyType strategyType) {
        this.strategyType = strategyType;
    }

    public String getSelectorScript() {
        return selectorScript;
    }

    public void setSelectorScript(String selectorScript) {
        this.selectorScript = selectorScript;
    }

    public String getFilterScript() {
        return filterScript;
    }

    public void setFilterScript(String filterScript) {
        this.filterScript = filterScript;
    }

    public boolean isAffinityEnabled() {
        return affinityEnabled;
    }

    public void setAffinityEnabled(boolean affinityEnabled) {
        this.affinityEnabled = affinityEnabled;
    }

    public int getAffinityIdleTimeoutSeconds() {
        return affinityIdleTimeoutSeconds;
    }

    public void setAffinityIdleTimeoutSeconds(int affinityIdleTimeoutSeconds) {
        this.affinityIdleTimeoutSeconds = affinityIdleTimeoutSeconds;
    }

    public int getAffinityMaxFailures() {
        return affinityMaxFailures;
    }

    public void setAffinityMaxFailures(int affinityMaxFailures) {
        this.affinityMaxFailures = affinityMaxFailures;
    }

    public int getLeaseTtlSeconds() {
        return leaseTtlSeconds;
    }

    public void setLeaseTtlSeconds(int leaseTtlSeconds) {
        this.leaseTtlSeconds = leaseTtlSeconds;
    }

    public String getStatePolicy() {
        return statePolicy;
    }

    public void setStatePolicy(String statePolicy) {
        this.statePolicy = statePolicy;
    }

    public String getDataProfile() {
        return dataProfile;
    }

    public void setDataProfile(String dataProfile) {
        this.dataProfile = dataProfile;
    }

    public String getRefreshUrl() {
        return refreshUrl;
    }

    public void setRefreshUrl(String refreshUrl) {
        this.refreshUrl = refreshUrl;
    }

    public int getRefreshIntervalSeconds() {
        return refreshIntervalSeconds;
    }

    public void setRefreshIntervalSeconds(int refreshIntervalSeconds) {
        this.refreshIntervalSeconds = refreshIntervalSeconds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
