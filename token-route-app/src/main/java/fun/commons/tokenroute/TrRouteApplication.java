package fun.commons.tokenroute;

import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;

/**
 * token-route 通用路由服务（端口 9302，Redis only）。
 * 排除链（thmp S0#7 教训）：Redis 全部交给 fwk4j MultiRedis 装配，
 * Boot RedisAutoConfiguration 与 Redisson starter 自动装配（V2/V4，4.6.1 只注册这两个）一律排除。
 */
@SpringBootApplication(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class,
        RedissonAutoConfigurationV2.class,
        RedissonAutoConfigurationV4.class
})
public class TrRouteApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrRouteApplication.class, args);
    }
}
