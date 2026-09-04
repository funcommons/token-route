package fun.commons.tokenroute.common;

/**
 * token-route 错误码（02_接口契约 §4）。
 * 通用段沿用 mc-api-spec / fwk4j ApiCode 数值（对齐测试 TrCodeTest 防漂移）；
 * 业务段仅 10630 / 10633 两个诊断码（10631/10632 随管理写面删除，10634~10639 预留）。
 */
import fun.commons.framework4j.api.ApiCode;

public enum TrCode {

    OK(ApiCode.SUCCESS.getCode(), "成功"),

    // —— 通用段（mc-api-spec，值与 fwk4j ApiCode 一致）——
    PARAM_ERROR(ApiCode.PARAM_ERROR.getCode(), "参数错误"),
    PARAM_MISSING(ApiCode.PARAM_MISSING.getCode(), "必填缺失"),
    UNAUTHORIZED(ApiCode.UNAUTHORIZED.getCode(), "认证失败"),
    NOT_FOUND(ApiCode.NOT_FOUND.getCode(), "资源不存在"),
    PARTIAL_SUCCESS(ApiCode.PARTIAL_SUCCESS.getCode(), "批量部分成功"),

    // —— 业务诊断段（日志/ops 展示用，不作为 API 错误返回）——
    /** 启动期：config 种子中脚本编译失败 → 拒绝启动（fail-fast，S1 启用） */
    SCRIPT_COMPILE_FAILED(10630, "脚本编译失败"),
    /** FEED 响应 / config 种子 schema 校验拒绝 → 本次拉取保旧值 + 告警 / 启动失败 */
    FEED_CONFIG_INVALID(10633, "FEED配置校验不通过");

    private final int code;
    private final String message;

    TrCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static java.util.Optional<TrCode> of(int code) {
        for (TrCode c : values()) {
            if (c.code == code) {
                return java.util.Optional.of(c);
            }
        }
        return java.util.Optional.empty();
    }
}
