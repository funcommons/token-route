package fun.commons.tokenroute.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 脚本引擎参数（05_脚本引擎规格 §2：执行超时中断）。
 */
@ConfigurationProperties(prefix = "tr.engine")
public class TrEngineProperties {

    /** 脚本执行超时（默认 100ms——兜底非预算，配置手册 §3） */
    private long scriptTimeoutMs = 100;

    public long getScriptTimeoutMs() {
        return scriptTimeoutMs;
    }

    public void setScriptTimeoutMs(long scriptTimeoutMs) {
        this.scriptTimeoutMs = scriptTimeoutMs;
    }
}
