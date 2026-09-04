package demo;

import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * token-route 嵌入式最小宿主（03_嵌入式SDK接入指南 §2 宿主形态）：
 * 引 token-route-starter + 提供 fwk4j MultiRedis → TrRouteEngine 等全套 bean 自动装配。
 * 排除链与 token-route-app 启动类一致（fwk4j-redis 传递的 Boot/Redisson 自动装配，
 * Redis 全部交给 fwk4j MultiRedis 装配）。
 */
@SpringBootApplication(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class,
        RedissonAutoConfigurationV2.class,
        RedissonAutoConfigurationV4.class
})
public class EmbeddedDemoApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(EmbeddedDemoApplication.class, args);
        int code = ctx.getBean(DemoRunner.class).runDemo();
        System.exit(SpringApplication.exit(ctx, () -> code));
    }
}
