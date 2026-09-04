package fun.commons.tokenroute.engine;

import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 表脚本装载器（01 §3.1：脚本以文件路径挂载，随部署制品评审/版本化；
 * 05 §3 编译 fail-fast——编译失败 → 10630 指名表/脚本 → 拒绝启动）。
 */
public class TrScriptLoader {

    private final TrScriptEngine engine;
    private final TrScriptRegistry registry;

    public TrScriptLoader(TrScriptEngine engine, TrScriptRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    /** 遍历表定义，编译全部 filter/selector 脚本并注册；失败抛 TrScriptCompileException */
    public TrScriptRegistry loadAll(TrTableRegistry tables) {
        for (TrTableDefinition t : tables.all()) {
            if (t.getFilterScript() != null && !t.getFilterScript().isBlank()) {
                compileAndRegister(t, t.getFilterScript(), t.getName() + TrScriptRegistry.FILTER_SUFFIX);
            }
            if (t.getSelectorScript() != null && !t.getSelectorScript().isBlank()) {
                compileAndRegister(t, t.getSelectorScript(), t.getName() + TrScriptRegistry.SELECTOR_SUFFIX);
            }
        }
        return registry;
    }

    private void compileAndRegister(TrTableDefinition table, String location, String key) {
        String source;
        try {
            source = read(location);
        } catch (IOException e) {
            // 读取失败同 10630 语义：坏脚本进不了线上（指名表与脚本位置）
            throw new TrScriptCompileException(table.getName() + " <- " + location, "脚本文件不可读: " + e.getMessage());
        }
        registry.register(key, engine.compile(key, source));
    }

    /** 支持 file: 前缀 / 文件系统路径 / classpath: 前缀 */
    static String read(String location) throws IOException {
        if (location.startsWith("classpath:")) {
            String res = location.substring("classpath:".length());
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(res)) {
                if (in == null) {
                    throw new IOException("classpath 资源不存在: " + res);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        String p = location.startsWith("file:") ? location.substring("file:".length()) : location;
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }
}
