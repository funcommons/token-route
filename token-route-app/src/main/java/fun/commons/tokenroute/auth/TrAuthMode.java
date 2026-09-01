package fun.commons.tokenroute.auth;

/**
 * 鉴权三模式（02_接口契约 §3，内网微服务无凭证签发/轮换）。
 */
public enum TrAuthMode {
    /** 内网信任，零鉴权头 */
    NONE,
    /** 静态共享密钥：X-Api-Key 比对，不符 → HTTP 401 */
    APIKEY,
    /** fwk4j-accesstoken 校验 Bearer JWT（只验不签），失败 → 200 + 10200 */
    JWT
}
