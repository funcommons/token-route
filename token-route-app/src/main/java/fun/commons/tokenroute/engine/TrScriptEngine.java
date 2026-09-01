package fun.commons.tokenroute.engine;

import groovy.lang.Binding;
import groovy.lang.Script;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.BreakStatement;
import org.codehaus.groovy.ast.stmt.ContinueStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Groovy 沙箱引擎（05_脚本引擎规格）：编译期 SecureAST 白名单 + 执行期超时中断 + 编译缓存。
 * <p>两用途（05 §4）：
 * <ul>
 *   <li>filter 谓词 (params, entry) → boolean：正常求值但非 Boolean → false（fail-closed，非故障）</li>
 *   <li>selector (params, entries) → entry_id：null/不存在/非 string → Optional.empty（回退默认策略，非故障）</li>
 * </ul>
 * 运行期异常/超时抛 {@link TrScriptExecutionException}——降级与计数由 resolve 层处理（05 §5）。
 */
public class TrScriptEngine {

    private static final Set<Class<?>> RECEIVER_WHITELIST = Set.of(
            TrScriptFunctions.class,
            Object.class, String.class, Number.class,
            Integer.class, Long.class, Double.class, Float.class,
            BigDecimal.class, BigInteger.class, Boolean.class, Character.class, Math.class,
            Map.class, List.class, Set.class, Collection.class, Iterable.class, ArrayList.class,
            groovy.lang.Closure.class, groovy.lang.Range.class);

    private final long timeoutMs;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, TrCompiledScript> cache = new ConcurrentHashMap<>();

    public TrScriptEngine(long scriptTimeoutMs) {
        this.timeoutMs = scriptTimeoutMs;
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tr-script-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.executor = Executors.newFixedThreadPool(4, tf);
    }

    /** 编译并缓存；白名单命中/语法错误 → 10630（指名脚本） */
    public TrCompiledScript compile(String name, String source) {
        TrCompiledScript compiled = cache.get(name);
        if (compiled != null) {
            return compiled;
        }
        synchronized (cache) {
            compiled = cache.get(name);
            if (compiled != null) {
                return compiled;
            }
            Class<? extends Script> clazz = doCompile(name, source);
            TrCompiledScript c = new TrCompiledScript(name, clazz);
            cache.put(name, c);
            return c;
        }
    }

    /** filter 谓词（05 §4）：结果非 Boolean → false（fail-closed，不算故障） */
    public boolean evalFilter(TrCompiledScript script, Map<String, Object> params,
                              Map<String, Object> entry, long now) {
        Binding binding = bindings(params, now);
        binding.setVariable("entry", entry);
        Object result = run(script, binding);
        return Boolean.TRUE.equals(result);
    }

    /** selector（05 §4）：返回候选集内 entry_id；null/不存在/非 string → empty（回退默认策略） */
    public Optional<String> evalSelector(TrCompiledScript script, Map<String, Object> params,
                                         List<Map<String, Object>> entries, long now) {
        Binding binding = bindings(params, now);
        binding.setVariable("entries", entries);
        Object result = run(script, binding);
        Set<String> candidateIds = new java.util.HashSet<>();
        for (Map<String, Object> e : entries) {
            Object id = e.get("entry_id");
            if (id != null) {
                candidateIds.add(String.valueOf(id));
            }
        }
        if (result instanceof String s && candidateIds.contains(s)) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    private Binding bindings(Map<String, Object> params, long now) {
        Binding binding = new Binding();
        binding.setVariable("params", params == null ? Map.of() : params);
        binding.setVariable("now", now);
        return binding;
    }

    private Object run(TrCompiledScript script, Binding binding) {
        Script instance = script.newInstance();
        instance.setBinding(binding);
        Future<Object> future = executor.submit(() -> (Object) instance.run());
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TrScriptTimeoutException(timeoutMs);
        } catch (Exception e) {
            throw new TrScriptExecutionException(script.name(), "求值", unwrap(e));
        }
    }

    private Throwable unwrap(Exception e) {
        return e instanceof java.util.concurrent.ExecutionException ee && ee.getCause() != null ? ee.getCause() : e;
    }

    private Class<? extends Script> doCompile(String name, String source) {
        try {
            CompilerConfiguration config = new CompilerConfiguration();
            config.addCompilationCustomizers(secureCustomizer(), new TrMethodBlacklistCustomizer(),
                    helperStaticImport());
            config.setScriptBaseClass(Script.class.getName());
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Script> clazz = (Class) new GroovyClassLoaderFacade(config).parse(source, name);
            return clazz;
        } catch (CompilationFailedException e) {
            throw new TrScriptCompileException(name, firstMessage(e));
        } catch (TrScriptCompileException e) {
            throw e;
        } catch (Exception e) {
            throw new TrScriptCompileException(name, String.valueOf(e));
        }
    }

    private String firstMessage(CompilationFailedException e) {
        if (e.getMessage() != null) {
            return e.getMessage();
        }
        return e.getCause() == null ? "编译失败" : String.valueOf(e.getCause());
    }

    /** 静态引入 helper（num/str/len/in/min/max → StaticMethodCallExpression，接收者类型白名单覆盖） */
    private org.codehaus.groovy.control.customizers.ImportCustomizer helperStaticImport() {
        return new org.codehaus.groovy.control.customizers.ImportCustomizer()
                .addStaticStars(TrScriptFunctions.class.getName());
    }

    /** 沙箱第一件套：编译期白名单（05 §2）——语句/表达式/接收者/静态引入四层 */
    private SecureASTCustomizer secureCustomizer() {
        SecureASTCustomizer secure = new SecureASTCustomizer();
        // 注：不启用 indirect import check——脚本上下文为动态类型（binding 变量），已知接收者走白名单；
        // 安全兜底 = import 黑名单 + 方法名黑名单（含禁 new）+ 接收者白名单（05 §2 三件套 + 补位件）
        secure.setIndirectImportCheckEnabled(false);
        secure.setMethodDefinitionAllowed(false);
        // 禁 import（敏感包一概拒绝；helper 静态引入走 ImportCustomizer，不受源码 import 影响）
        secure.setImportsBlacklist(List.of("java.io", "java.nio", "java.net", "java.sql", "javax",
                "java.lang.reflect", "java.lang.Process", "java.lang.ProcessBuilder", "java.lang.Runtime",
                "java.lang.System", "java.lang.Thread", "java.security", "java.util.concurrent",
                "groovy.json", "groovy.util", "groovy.lang.GroovyShell", "groovy.lang.GroovyClassLoader"));
        secure.setStarImportsBlacklist(List.of("java.io", "java.nio", "java.net", "java.sql", "javax"));
        // 注：staticImportsBlacklist 与 staticStarImportsWhitelist 同属静态引入类别，二者只能取其一；
        // System/Runtime/Thread 的静态调用已由接收者白名单 + 方法名黑名单双重覆盖
        // 语句白名单（不含基类 Statement——基类即全放行；无 while/for/do-while → while(true) 编译期拒绝）
        secure.setStatementsWhitelist(List.of(
                BlockStatement.class, ExpressionStatement.class,
                ReturnStatement.class, IfStatement.class, AssertStatement.class,
                BreakStatement.class, ContinueStatement.class));
        // 表达式白名单（含闭包——集合遍历只走白名单闭包方法，05 §2；无 ConstructorCall/Class 字面量 → 禁 new 与类引用）
        secure.setExpressionsWhitelist(List.of(
                VariableExpression.class, ConstantExpression.class,
                DeclarationExpression.class, BinaryExpression.class,
                UnaryMinusExpression.class, UnaryPlusExpression.class,
                NotExpression.class, BitwiseNegationExpression.class,
                PrefixExpression.class, PostfixExpression.class,
                TernaryExpression.class, ElvisOperatorExpression.class,
                BooleanExpression.class,
                PropertyExpression.class, AttributeExpression.class,
                MethodCallExpression.class, StaticMethodCallExpression.class,
                ArgumentListExpression.class, TupleExpression.class,
                ListExpression.class, MapExpression.class,
                MapEntryExpression.class, NamedArgumentListExpression.class,
                RangeExpression.class, GStringExpression.class,
                CastExpression.class, ClosureExpression.class));
        // 接收者类型白名单（禁 System/Runtime/Thread/IO/反射/Script.evaluate）
        secure.setAllowedReceivers(RECEIVER_WHITELIST.stream().map(Class::getName).toList());
        // helper 静态星引入白名单
        secure.setStaticStarImportsWhitelist(List.of(TrScriptFunctions.class.getName()));
        return secure;
    }

    /** GroovyClassLoader 门面（隔离 groovy 依赖面；构造期绑定沙箱 CompilerConfiguration） */
    private static final class GroovyClassLoaderFacade extends groovy.lang.GroovyClassLoader {
        GroovyClassLoaderFacade(CompilerConfiguration config) {
            super(GroovyClassLoaderFacade.class.getClassLoader(), config);
        }

        Class<?> parse(String source, String name) {
            // 类名加 nonce 防同源脚本类名冲突；结果交由 TrCompiledScript 缓存持有
            return parseClass(source, name + "-" + Long.toHexString(System.nanoTime()));
        }
    }
}
