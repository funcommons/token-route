package fun.commons.tokenroute.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * tr.* 运行时参数（tr.key-prefix 等；鉴权见 TrAuthProperties，表种子见 TrTablesProperties）。
 */
@ConfigurationProperties(prefix = "tr")
public class TrProperties {

    /** Redis 键前缀（03_数据设计 §1） */
    private String keyPrefix = "tr";

    /** fwk4j MultiRedis datasource 名（03_数据设计 §1：datasource main） */
    private String redisName = "main";

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getRedisName() {
        return redisName;
    }

    public void setRedisName(String redisName) {
        this.redisName = redisName;
    }
}
