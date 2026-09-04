package fun.commons.tokenroute.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 鉴权配置（02_接口契约 §3）：两面各一个模式配置项 + 亲和管理端点组内部鉴权，切换 = 改配置滚动重启。
 */
@ConfigurationProperties(prefix = "tr.auth")
public class TrAuthProperties {

    /** 契约面（/v1/resolve /v1/report /v1/affinity/** /v1/refresh/**）；jwt 模式 policy tr-client */
    private TrAuthMode contractMode = TrAuthMode.NONE;

    /** 运维只读面（/v1/ops/**）；默认 jwt（policy tr-admin），生产保持 jwt */
    private TrAuthMode opsMode = TrAuthMode.JWT;

    /** apikey 模式共享密钥（env 注入） */
    private String apiKey = "";

    /** 亲和管理端点组（/v1/admin/affinity/**，零管理写面唯一例外，issue #1）：内部令牌 + 可信 IP */
    private AffinityAdmin affinityAdmin = new AffinityAdmin();

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

    public AffinityAdmin getAffinityAdmin() {
        return affinityAdmin;
    }

    public void setAffinityAdmin(AffinityAdmin affinityAdmin) {
        this.affinityAdmin = affinityAdmin;
    }

    /** 亲和管理端点鉴权（02_接口契约 §3.3）：与 MMagiX 内部服务鉴权同模式 */
    public static class AffinityAdmin {

        /** X-Internal-Service-Token 比对值（env 注入）；空/未配置 = 端点整体禁用（全部 401，安全缺省） */
        private String internalToken = "";

        /** 可信来源 IP 白名单；空 = 不校验来源 IP */
        private List<String> trustedIps = new ArrayList<>();

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }

        public List<String> getTrustedIps() {
            return trustedIps;
        }

        public void setTrustedIps(List<String> trustedIps) {
            this.trustedIps = trustedIps;
        }
    }
}
