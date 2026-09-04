package fun.commons.tokenroute.engine;

/**
 * 脚本运行期故障（超时/求值异常）——调用方按 05 §5 降级：filter 出局 / selector 回退默认策略 + 计数。
 */
public class TrScriptExecutionException extends RuntimeException {

    public TrScriptExecutionException(String scriptName, String phase, Throwable cause) {
        super("脚本执行失败: " + scriptName + " (" + phase + "): " + cause, cause);
    }
}
