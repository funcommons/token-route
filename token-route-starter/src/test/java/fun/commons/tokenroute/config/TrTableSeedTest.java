package fun.commons.tokenroute.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * config 种子加载测试——01_设计方案 §4.1 表定义 / §5.6 生效链（schema 校验 fail-fast，10633 指名表）。
 */
class TrTableSeedTest {

    private TrTableDefinition seed(String name, String refreshUrl) {
        TrTableDefinition d = new TrTableDefinition();
        d.setName(name);
        d.setRefreshUrl(refreshUrl);
        return d;
    }

    @Test
    void minimalSeedAppliesDocumentedDefaults() {
        // 01 §4.1：亲和空闲超时默认 8h(28800) / 连败脱离阈值默认 5 / 租约 TTL 默认 30s / 刷新周期默认 60s
        TrTableRegistry registry = TrTableRegistry.load(List.of(seed("llm-supply", "http://up/feed")));

        TrTableDefinition t = registry.find("llm-supply").orElseThrow();
        assertThat(t.getTenantId()).isEqualTo("0");
        assertThat(t.getStrategyType()).isEqualTo(TrStrategyType.WEIGHTED_RANDOM);
        assertThat(t.isAffinityEnabled()).isTrue();
        assertThat(t.getAffinityIdleTimeoutSeconds()).isEqualTo(28800);
        assertThat(t.getAffinityMaxFailures()).isEqualTo(5);
        assertThat(t.getLeaseTtlSeconds()).isEqualTo(30);
        assertThat(t.getStatePolicy()).isEqualTo("default");
        assertThat(t.getDataProfile()).isEqualTo("generic");
        assertThat(t.getRefreshIntervalSeconds()).isEqualTo(60);
        assertThat(t.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void metadataIsOptionalAndRoundTrips() {
        // issue #3 R2：策略系统发布种子写入版本追溯标记，不进内核语义；缺省为 null 不影响加载
        TrTableDefinition withMeta = seed("meta-t", "http://up/feed");
        withMeta.setMetadata(java.util.Map.of(
                "strategy-version", "tokengo-v12", "published-at", "2026-09-05T03:00:00Z"));
        TrTableRegistry registry = TrTableRegistry.load(List.of(withMeta, seed("plain", "http://up/feed")));

        assertThat(registry.find("meta-t").orElseThrow().getMetadata())
                .containsEntry("strategy-version", "tokengo-v12")
                .containsEntry("published-at", "2026-09-05T03:00:00Z");
        assertThat(registry.find("plain").orElseThrow().getMetadata()).isNull();
    }

    @Test
    void loadRejectsMissingName_withCode10633() {
        TrTableDefinition bad = new TrTableDefinition();
        bad.setRefreshUrl("http://up/feed");
        assertThatThrownBy(() -> TrTableRegistry.load(List.of(bad)))
                .isInstanceOf(TrSeedConfigException.class)
                .hasMessageContaining("10633")
                .hasMessageContaining("name");
    }

    @Test
    void loadRejectsMissingRefreshUrl_named() {
        TrTableDefinition bad = new TrTableDefinition();
        bad.setName("llm-supply");
        assertThatThrownBy(() -> TrTableRegistry.load(List.of(bad)))
                .isInstanceOf(TrSeedConfigException.class)
                .hasMessageContaining("10633")
                .hasMessageContaining("llm-supply")
                .hasMessageContaining("refresh_url");
    }

    @Test
    void loadRejectsDuplicateTableNames() {
        assertThatThrownBy(() -> TrTableRegistry.load(List.of(
                seed("dup", "http://a/feed"), seed("dup", "http://b/feed"))))
                .isInstanceOf(TrSeedConfigException.class)
                .hasMessageContaining("dup")
                .hasMessageContaining("重复");
    }

    @Test
    void scriptStrategyRequiresSelectorScript() {
        TrTableDefinition bad = seed("s-table", "http://up/feed");
        bad.setStrategyType(TrStrategyType.SCRIPT);
        assertThatThrownBy(() -> TrTableRegistry.load(List.of(bad)))
                .isInstanceOf(TrSeedConfigException.class)
                .hasMessageContaining("s-table")
                .hasMessageContaining("selector_script");
    }

    @Test
    void rejectsNonPositiveTuningParams() {
        TrTableDefinition zeroLease = seed("t", "http://up/feed");
        zeroLease.setLeaseTtlSeconds(0);
        assertThatThrownBy(() -> TrTableRegistry.load(List.of(zeroLease)))
                .isInstanceOf(TrSeedConfigException.class)
                .hasMessageContaining("lease_ttl_seconds");

        TrTableDefinition zeroInterval = seed("t2", "http://up/feed");
        zeroInterval.setRefreshIntervalSeconds(-5);
        assertThatThrownBy(() -> TrTableRegistry.load(List.of(zeroInterval)))
                .isInstanceOf(TrSeedConfigException.class)
                .hasMessageContaining("refresh_interval_seconds");
    }

    @Test
    void offlineTableRegistersButIsNotOnline() {
        TrTableDefinition off = seed("off-table", "http://up/feed");
        off.setStatus("OFFLINE");
        TrTableRegistry registry = TrTableRegistry.load(List.of(seed("on-table", "http://up/feed"), off));

        assertThat(registry.exists("off-table")).isTrue();
        assertThat(registry.isOnline("off-table")).isFalse();
        assertThat(registry.isOnline("on-table")).isTrue();
        assertThat(registry.isOnline("ghost")).isFalse();
    }

    @Test
    void findReturnsEmptyForUnknownTable() {
        TrTableRegistry registry = TrTableRegistry.load(List.of(seed("t", "http://up/feed")));
        assertThat(registry.find("ghost")).isEmpty();
    }
}
