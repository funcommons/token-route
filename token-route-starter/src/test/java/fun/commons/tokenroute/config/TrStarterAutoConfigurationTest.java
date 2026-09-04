package fun.commons.tokenroute.config;

import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.TrRouteEngine;
import fun.commons.tokenroute.admin.TrAffinityAdminService;
import fun.commons.tokenroute.admin.TrStateAdminService;
import fun.commons.tokenroute.feed.TrEvaluateJob;
import fun.commons.tokenroute.feed.TrEvaluatorScheduler;
import fun.commons.tokenroute.observe.TrMetrics;
import fun.commons.tokenroute.resolve.TrResolveService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 嵌入式自动装配矩阵（03_嵌入式SDK接入指南）：
 * 引依赖 + 提供 MultiRedisManager → 引擎全套 bean 就位；tr.enabled=false 整体关闭；
 * 评估器随 tr.scheduler.enabled 关；坏表种子启动 fail-fast（10633）。
 */
class TrStarterAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TrStarterAutoConfiguration.class))
            .withUserConfiguration(HostConfiguration.class);

    /** 宿主形态：提供 MultiRedisManager（fwk4j-redis 装配产物；测试内直给） */
    @Configuration(proxyBeanMethods = false)
    static class HostConfiguration {
        @Bean
        MultiRedisManager multiRedisManager() {
            return new MultiRedisManager();
        }
    }

    @Test
    void engineBeansAssembledOnClasspath() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(TrRouteEngine.class);
            assertThat(ctx).hasSingleBean(TrResolveService.class);
            assertThat(ctx).hasSingleBean(TrTableRegistry.class);
            assertThat(ctx).hasSingleBean(TrMetrics.class);          // 无 MeterRegistry → noop
            assertThat(ctx).hasSingleBean(TrAffinityAdminService.class);
            assertThat(ctx).hasSingleBean(TrStateAdminService.class);
            assertThat(ctx).hasSingleBean(TrEvaluateJob.class);      // 评估器默认开
            assertThat(ctx).hasSingleBean(TrEvaluatorScheduler.class);
        });
    }

    @Test
    void disabledByTrEnabledFalse() {
        runner.withPropertyValues("tr.enabled=false").run(ctx ->
                assertThat(ctx).doesNotHaveBean(TrRouteEngine.class));
    }

    @Test
    void evaluatorOffBySchedulerEnabledFalse() {
        runner.withPropertyValues("tr.scheduler.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(TrEvaluateJob.class);
            assertThat(ctx).doesNotHaveBean(TrEvaluatorScheduler.class);
            assertThat(ctx).hasSingleBean(TrRouteEngine.class);      // 引擎本体不受影响
        });
    }

    @Test
    void tableSeedFailsFastOnBadSeed() {
        runner.withPropertyValues(
                        "tr.tables[0].name=llm-supply",
                        "tr.tables[0].strategy-type=WEIGHTED_RANDOM") // 缺 refresh_url → 10633
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
