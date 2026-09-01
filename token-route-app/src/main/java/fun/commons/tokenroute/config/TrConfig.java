package fun.commons.tokenroute.config;

import fun.commons.framework4j.accesstoken.config.AccessTokenProperties;
import fun.commons.framework4j.accesstoken.core.AccessTokenGenerator;
import fun.commons.framework4j.accesstoken.interceptor.AccessTokenValidationStrategy;
import fun.commons.tokenroute.auth.TrAuthInterceptor;
import fun.commons.tokenroute.auth.TrAuthProperties;
import fun.commons.tokenroute.engine.TrEngineProperties;
import fun.commons.tokenroute.engine.TrScriptEngine;
import fun.commons.tokenroute.engine.TrScriptLoader;
import fun.commons.tokenroute.engine.TrScriptRegistry;
import fun.commons.tokenroute.keyspace.TrKeySpace;
import fun.commons.tokenroute.redis.TrRedis;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * token-route 装配：config 种子加载（启动 fail-fast）+ 键工厂 + 鉴权拦截器。
 */
@Configuration
@EnableConfigurationProperties({TrProperties.class, TrTablesProperties.class, TrAuthProperties.class,
        TrEngineProperties.class})
public class TrConfig implements WebMvcConfigurer {

    private final TrProperties properties;
    private final TrTablesProperties tablesProperties;
    private final TrAuthProperties authProperties;
    private final AccessTokenProperties accessTokenProperties;
    private final AccessTokenValidationStrategy jwtStrategy;

    public TrConfig(TrProperties properties,
                    TrTablesProperties tablesProperties,
                    TrAuthProperties authProperties,
                    AccessTokenProperties accessTokenProperties,
                    AccessTokenGenerator accessTokenGenerator,
                    @Qualifier("accessTokenStringRedisTemplate") StringRedisTemplate accessTokenRedisTemplate) {
        this.properties = properties;
        this.tablesProperties = tablesProperties;
        this.authProperties = authProperties;
        this.accessTokenProperties = accessTokenProperties;
        // 对齐 fwk4j TokenInterceptor 内部做法：编程式构造校验策略（策略非容器 Bean）
        this.jwtStrategy = new AccessTokenValidationStrategy(accessTokenGenerator, accessTokenRedisTemplate);
    }

    /** config 种子加载：schema 校验失败 → 10633 指名表 → 拒绝启动（01 §5.6） */
    @Bean
    public TrTableRegistry trTableRegistry() {
        return TrTableRegistry.load(tablesProperties.getTables());
    }

    @Bean
    public TrScriptEngine trScriptEngine(TrEngineProperties engineProperties) {
        return new TrScriptEngine(engineProperties.getScriptTimeoutMs());
    }

    /** 脚本编译 fail-fast（05 §3）：启动加载即编译全部表脚本，失败 → 10630 拒绝启动 */
    @Bean
    public TrScriptRegistry trScriptRegistry(TrTableRegistry tables, TrScriptEngine engine) {
        return new TrScriptLoader(engine, new TrScriptRegistry()).loadAll(tables);
    }

    @Bean
    public TrKeySpace trKeySpace() {
        return new TrKeySpace(properties.getKeyPrefix());
    }

    @Bean
    public TrRedis trRedis(fun.commons.framework4j.redis.manager.MultiRedisManager manager) {
        return new TrRedis(manager, properties.getRedisName());
    }

    /** SLI#8 Redis 连通性（/actuator/health 组件 tokenRouteRedis）；显式命名避开业务 bean 'trRedis'
     *  ——Boot 会把 xxxHealthIndicator bean 推断为健康键 xxx，默认类名会与上方业务 bean 冲突 */
    @Bean
    public org.springframework.boot.actuate.health.HealthIndicator tokenRouteRedisHealth(TrRedis redis) {
        return new fun.commons.tokenroute.observe.TrRedisHealthIndicator(redis);
    }

    /** 应用时钟（窗口 score / lease deadline / Lua now；03 §1，测试可替换） */
    @Bean
    public java.time.Clock trClock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    public TrAuthInterceptor trAuthInterceptor() {
        return new TrAuthInterceptor(authProperties, accessTokenProperties, jwtStrategy);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 契约面与 ops 面共用一个拦截器，内部按 /v1/ops/ 前缀取各自模式
        registry.addInterceptor(trAuthInterceptor()).addPathPatterns("/v1/**");
    }
}
