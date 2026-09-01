package fun.commons.tokenroute.engine;

/**
 * 脚本执行超时（05 §2：超时中断，默认 100ms）。
 */
public class TrScriptTimeoutException extends RuntimeException {

    public TrScriptTimeoutException(long timeoutMs) {
        super("脚本执行超时中断: " + timeoutMs + "ms");
    }
}
