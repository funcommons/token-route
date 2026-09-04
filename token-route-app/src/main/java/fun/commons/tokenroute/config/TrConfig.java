package fun.commons.tokenroute.config;

import fun.commons.framework4j.accesstoken.config.AccessTokenProperties;
import fun.commons.framework4j.accesstoken.core.AccessTokenGenerator;
import fun.commons.framework4j.accesstoken.interceptor.AccessTokenValidationStrategy;
import fun.commons.tokenroute.auth.TrAffinityAdminInterceptor;
import fun.commons.tokenroute.auth.TrAuthInterceptor;
import fun.commons.tokenroute.auth.TrAuthProperties;
import fun.commons.tokenroute.observe.TrRedisHealthIndicator;
import fun.commons.tokenroute.redis.TrRedis;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * token-route HTTP 壳装配：两面三模式鉴权 + 亲和管理内部鉴权 + Redis 健康组件。
 * 核心引擎（表种子/脚本/resolve/report/ops/admin/评估器/TrRouteEngine）由
 * token-route-starter 自动装配提供（TrStarterAutoConfiguration），本类不再注册核心 bean。
 */
@Configuration
@EnableConfigurationProperties(TrAuthProperties.class)
public class TrConfig implements WebMvcConfigurer {

    private final TrAuthProperties authProperties;
    private final AccessTokenProperties accessTokenProperties;
    private final AccessTokenValidationStrategy jwtStrategy;

    public TrConfig(TrAuthProperties authProperties,
                    AccessTokenProperties accessTokenProperties,
                    AccessTokenGenerator accessTokenGenerator,
                    @Qualifier("accessTokenStringRedisTemplate") StringRedisTemplate accessTokenRedisTemplate) {
        this.authProperties = authProperties;
        this.accessTokenProperties = accessTokenProperties;
        // 对齐 fwk4j TokenInterceptor 内部做法：编程式构造校验策略（策略非容器 Bean）
        this.jwtStrategy = new AccessTokenValidationStrategy(accessTokenGenerator, accessTokenRedisTemplate);
    }

    /** SLI#8 Redis 连通性（/actuator/health 组件 tokenRouteRedis）；显式命名避开业务 bean 'trRedis'
     *  ——Boot 会把 xxxHealthIndicator bean 推断为健康键 xxx，默认类名会与 starter 业务 bean 冲突 */
    @Bean
    public org.springframework.boot.actuate.health.HealthIndicator tokenRouteRedisHealth(TrRedis redis) {
        return new TrRedisHealthIndicator(redis);
    }

    @Bean
    public TrAuthInterceptor trAuthInterceptor() {
        return new TrAuthInterceptor(authProperties, accessTokenProperties, jwtStrategy);
    }

    /** 亲和管理端点内部鉴权（零管理写面唯一例外）：不走两面三模式 */
    @Bean
    public TrAffinityAdminInterceptor trAffinityAdminInterceptor() {
        return new TrAffinityAdminInterceptor(authProperties);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 契约面与 ops 面共用一个拦截器，内部按 /v1/ops/ 前缀取各自模式；
        // /v1/admin/** 走亲和管理内部鉴权（令牌 + 可信 IP），与三模式互斥
        registry.addInterceptor(trAuthInterceptor())
                .addPathPatterns("/v1/**")
                .excludePathPatterns("/v1/admin/**");
        registry.addInterceptor(trAffinityAdminInterceptor()).addPathPatterns("/v1/admin/**");
    }
}
