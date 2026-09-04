package fun.commons.tokenroute.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.redis.manager.MultiRedisManager;
import fun.commons.tokenroute.TrRouteEngine;
import fun.commons.tokenroute.admin.TrAffinityAdminService;
import fun.commons.tokenroute.admin.TrStateAdminService;
import fun.commons.tokenroute.engine.TrEngineProperties;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.feed.TrEvaluateJob;
import fun.commons.tokenroute.feed.TrEvaluatorScheduler;
import fun.commons.tokenroute.feed.TrFeedRefreshService;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.observe.TrMetrics;
import fun.commons.tokenroute.ops.TrOpsService;
import fun.commons.tokenroute.redis.TrRedis;
import fun.commons.tokenroute.report.TrReportService;
import fun.commons.tokenroute.resolve.TrResolveService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 嵌入式核心自动装配（docs/用户文档/03_嵌入式SDK接入指南.md）：
 * 宿主引入本 starter + 提供 {@link MultiRedisManager}（fwk4j-redis 装配产物）即得全套路由引擎 bean，
 * 进程内直调 {@link TrRouteEngine}——零网络跳（01 §7.2 嵌入式形态）。
 * <ul>
 *   <li>总开关 {@code tr.enabled=false} 可整体关闭（默认开——引依赖即显式意图）；</li>
 *   <li>表种子 {@code tr.tables} 随宿主配置，坏种子启动 fail-fast（10633 指名表）；</li>
 *   <li>评估器 {@code tr.scheduler.enabled=false} 可关（默认开，自管 daemon 线程，不动宿主调度语义）；</li>
 *   <li>宿主已有 MeterRegistry 则指标自动埋点（tr.* 前缀），否则 noop；</li>
 *   <li>与独立部署的 token-route 服务可共用同一 Redis 键空间混布（状态全在 Redis）。</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties({TrProperties.class, TrTablesProperties.class, TrEngineProperties.class})
@ConditionalOnProperty(name = "tr.enabled", havingValue = "true", matchIfMissing = true)
public class TrStarterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper trObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock trClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public TrMetrics trMetrics(ObjectProvider<MeterRegistry> registry) {
        return new TrMetrics(registry.getIfAvailable());
    }

    @Bean
    public TrKeySpace trKeySpace(TrProperties properties) {
        return new TrKeySpace(properties.getKeyPrefix());
    }

    /** 宿主缺 MultiRedisManager bean → 启动失败（fail-fast：fwk4j-redis 装配产物，见接入指南） */
    @Bean
    public TrRedis trRedis(MultiRedisManager manager, TrProperties properties) {
        return new TrRedis(manager, properties.getRedisName());
    }

    /** 表种子加载：schema 校验失败 → 拒绝启动（01 §5.6） */
    @Bean
    public TrTableRegistry trTableRegistry(TrTablesProperties tables) {
        return TrTableRegistry.load(tables.getTables());
    }

    @Bean
    public TrScriptEngine trScriptEngine(TrEngineProperties engineProperties) {
        return new TrScriptEngine(engineProperties.getScriptTimeoutMs());
    }

    /** 脚本编译 fail-fast（05 §3）：启动加载即编译全部表脚本 */
    @Bean
    public TrScriptRegistry trScriptRegistry(TrTableRegistry tables, TrScriptEngine engine) {
        return new TrScriptLoader(engine, new TrScriptRegistry()).loadAll(tables);
    }

    @Bean
    public TrFeedRefreshService trFeedRefreshService(TrRedis redis, TrKeySpace keys,
                                                     TrTableRegistry registry, ObjectMapper mapper,
                                                     Clock clock, TrMetrics metrics) {
        return new TrFeedRefreshService(redis, keys, registry, mapper, clock, metrics);
    }

    @Bean
    public TrResolveService trResolveService(TrRedis redis, TrKeySpace keys, TrTableRegistry registry,
                                             TrScriptRegistry scripts, TrScriptEngine engine,
                                             TrFeedRefreshService feed, ObjectMapper mapper,
                                             Clock clock, TrMetrics metrics) {
        return new TrResolveService(redis, keys, registry, scripts, engine, feed, mapper, clock, metrics);
    }

    @Bean
    public TrReportService trReportService(TrRedis redis, TrKeySpace keys, TrTableRegistry registry,
                                           Clock clock, TrMetrics metrics) {
        return new TrReportService(redis, keys, registry, clock, metrics);
    }

    @Bean
    public TrOpsService trOpsService(TrRedis redis, TrKeySpace keys, TrTableRegistry registry,
                                     ObjectMapper mapper) {
        return new TrOpsService(redis, keys, registry, mapper);
    }

    @Bean
    public TrAffinityAdminService trAffinityAdminService(TrRedis redis, TrKeySpace keys,
                                                         TrTableRegistry registry,
                                                         TrFeedRefreshService feed,
                                                         ObjectMapper mapper, Clock clock) {
        return new TrAffinityAdminService(redis, keys, registry, feed, mapper, clock);
    }

    @Bean
    public TrStateAdminService trStateAdminService(TrRedis redis, TrKeySpace keys,
                                                   TrTableRegistry registry,
                                                   TrFeedRefreshService feed, Clock clock) {
        return new TrStateAdminService(redis, keys, registry, feed, clock);
    }

    /** 进程内门面：resolve / report / detach 直调内核 */
    @Bean
    public TrRouteEngine trRouteEngine(TrResolveService resolveService, TrReportService reportService,
                                       TrRedis redis, TrKeySpace keys, ObjectMapper mapper, Clock clock) {
        return new TrRouteEngine(resolveService, reportService, redis, keys, mapper, clock);
    }

    /** 评估器节拍（01 §5.3：fixedDelay 60s；Redisson 多实例互斥在 Job 内）——自管 daemon 线程 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "tr.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    public static class EvaluatorConfiguration {

        @Bean
        public TrEvaluateJob trEvaluateJob(TrRedis redis, TrKeySpace keys, TrTableRegistry registry,
                                           MultiRedisManager redisManager, TrProperties properties,
                                           Clock clock, TrMetrics metrics) {
            return new TrEvaluateJob(redis, keys, registry, redisManager, properties, clock, metrics);
        }

        @Bean
        public TrEvaluatorScheduler trEvaluatorScheduler(TrEvaluateJob job) {
            return new TrEvaluatorScheduler(job);
        }
    }
}
