package fun.commons.tokenroute.engine;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

import java.util.Set;

/**
 * 方法名黑名单定制器（Groovy 4 SecureASTCustomizer 无方法级/构造器级 API 的补位件，05 §2）：
 * 编译期拒绝危险方法调用/属性访问，并全面禁 new（05 §6 示例不使用 new，从严处理）。
 */
class TrMethodBlacklistCustomizer extends CompilationCustomizer {

    /** 编译期拒绝的方法名/属性名（逃逸向量：execute/evaluate/反射/进程/线程/环境/原始字节） */
    private static final Set<String> BLACKLISTED_METHODS = Set.of(
            "execute", "evaluate", "eval", "getRuntime", "forName", "loadClass",
            "newInstance", "sleep", "exit", "halt", "invoke",
            "getMethod", "getDeclaredMethod", "getDeclaredMethods", "getMethods",
            "getResource", "getResourceAsStream", "getSystemResource",
            "setSecurityManager", "getSecurityManager", "getenv", "getProperty",
            "currentThread", "readObject", "writeObject", "shell", "executeCommand",
            "bytes", "getBytes", "chars", "classLoader", "protectionDomain");

    TrMethodBlacklistCustomizer() {
        super(CompilePhase.CONVERSION);
    }

    @Override
    public void call(SourceUnit source, org.codehaus.groovy.classgen.GeneratorContext context,
                     ClassNode classNode) throws CompilationFailedException {
        new Visitor(source).visitClass(classNode);
    }

    private static final class Visitor extends ClassCodeVisitorSupport {
        private final SourceUnit source;

        Visitor(SourceUnit source) {
            this.source = source;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return source;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = nameOf(call.getMethod());
            if (name != null && BLACKLISTED_METHODS.contains(name)) {
                addError("禁止调用方法: " + name, call);
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            if (BLACKLISTED_METHODS.contains(call.getMethod())) {
                addError("禁止静态调用方法: " + call.getMethod(), call);
            }
            super.visitStaticMethodCallExpression(call);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression prop) {
            String name = nameOf(prop.getProperty());
            if (name != null && BLACKLISTED_METHODS.contains(name)) {
                addError("禁止访问属性: " + name, prop);
            }
            super.visitPropertyExpression(prop);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression ctor) {
            // 放行脚本类自身的 super()/this() 特殊构造调用；用户显式 new 一律拒绝
            if (!ctor.isSpecialCall()) {
                addError("禁止构造对象: new 不可用", ctor);
            }
            super.visitConstructorCallExpression(ctor);
        }

        private String nameOf(Object node) {
            if (node instanceof org.codehaus.groovy.ast.expr.ConstantExpression ce) {
                Object v = ce.getValue();
                return v instanceof String s ? s : null;
            }
            return null;
        }
    }
}
