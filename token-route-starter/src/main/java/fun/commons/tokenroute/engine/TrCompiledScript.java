package fun.commons.tokenroute.engine;

import groovy.lang.Script;

/**
 * 已编译脚本（05 §2 编译缓存条目；实例不共享——每次求值 new Script 实例 + 独立 Binding）。
 */
public final class TrCompiledScript {

    private final String name;
    private final Class<? extends Script> scriptClass;
    private final long compiledAt;

    TrCompiledScript(String name, Class<? extends Script> scriptClass) {
        this.name = name;
        this.scriptClass = scriptClass;
        this.compiledAt = System.currentTimeMillis();
    }

    public String name() {
        return name;
    }

    public Class<? extends Script> scriptClass() {
        return scriptClass;
    }

    public long compiledAt() {
        return compiledAt;
    }

    Script newInstance() {
        try {
            return scriptClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new TrScriptExecutionException(name, "实例化", e);
        }
    }
}
