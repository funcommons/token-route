package fun.commons.tokenroute.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 鉴权配置（02_接口契约 §3）：两面各一个模式配置项，切换 = 改配置滚动重启。
 */
@ConfigurationProperties(prefix = "tr.auth")
public class TrAuthProperties {

    /** 契约面（/v1/resolve /v1/report /v1/affinity/** /v1/refresh/**）；jwt 模式 policy tr-client */
    private TrAuthMode contractMode = TrAuthMode.NONE;

    /** 运维只读面（/v1/ops/**）；默认 jwt（policy tr-admin），生产保持 jwt */
    private TrAuthMode opsMode = TrAuthMode.JWT;

    /** apikey 模式共享密钥（env 注入） */
    private String apiKey = "";

    public TrAuthMode getContractMode() {
        return contractMode;
    }

    public void setContractMode(TrAuthMode contractMode) {
        this.contractMode = contractMode;
    }

    public TrAuthMode getOpsMode() {
        return opsMode;
    }

    public void setOpsMode(TrAuthMode opsMode) {
        this.opsMode = opsMode;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
