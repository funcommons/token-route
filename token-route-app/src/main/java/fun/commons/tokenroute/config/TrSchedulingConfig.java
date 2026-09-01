package fun.commons.tokenroute.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度开关（07 §4：CI 无 Docker 环境跑 tr.scheduler.enabled=false）。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "tr.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TrSchedulingConfig {
}
