package fun.commons.tokenroute.engine;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已编译脚本注册表（按 "table:{tid}:{用途}" 键索引；S2 resolve 按 key 取用）。
 */
public final class TrScriptRegistry {

    public static final String FILTER_SUFFIX = ":filter";
    public static final String SELECTOR_SUFFIX = ":selector";

    private final ConcurrentHashMap<String, TrCompiledScript> scripts = new ConcurrentHashMap<>();

    void register(String key, TrCompiledScript compiled) {
        scripts.put(key, compiled);
    }

    public Optional<TrCompiledScript> find(String key) {
        return Optional.ofNullable(scripts.get(key));
    }

    public Set<String> keys() {
        return scripts.keySet();
    }
}
