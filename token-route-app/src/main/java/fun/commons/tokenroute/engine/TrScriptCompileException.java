package fun.commons.tokenroute.engine;

import fun.commons.tokenroute.common.TrCode;

/**
 * 脚本编译失败（05 §5 启动期：10630 拒绝启动，消息指名脚本）。
 */
public class TrScriptCompileException extends RuntimeException {

    public TrScriptCompileException(String scriptName, String detail) {
        super("[" + TrCode.SCRIPT_COMPILE_FAILED.getCode() + "] 脚本编译失败: " + scriptName + " — " + detail);
    }
}
