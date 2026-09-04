package fun.commons.tokenroute.config;

/**
 * config 种子 schema 校验失败（02_接口契约 §4.2 诊断码 10633）。
 * 启动加载即校验，失败 → 拒绝启动（fail-fast），消息指名表/字段。
 */
public class TrSeedConfigException extends RuntimeException {

    public TrSeedConfigException(String message) {
        super("[10633] " + message);
    }
}
