package fun.commons.tokenroute.redis;

import fun.commons.framework4j.redis.manager.MultiRedisManager;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 数据面门面：统一经 fwk4j MultiRedis（03_数据设计 §1，datasource 由 tr.redis-name 指定，默认 main）。
 * 禁止绕过本类直接持有 template——故障语义（resolve EMPTY / report 10700）集中在此扩展。
 */
public class TrRedis {

    private final MultiRedisManager manager;
    private final String datasource;

    public TrRedis(MultiRedisManager manager, String datasource) {
        this.manager = manager;
        this.datasource = datasource;
    }

    public StringRedisTemplate stringTemplate() {
        return manager.getStringRedisTemplate(datasource);
    }

    public boolean healthy() {
        return manager.checkHealth(datasource);
    }
}
