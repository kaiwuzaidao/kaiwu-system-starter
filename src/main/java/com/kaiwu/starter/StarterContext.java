package com.kaiwu.starter;

/**
 * 当前请求的 Gateway Context。
 *
 * <p>只允许由 Starter 拦截器写入，并在请求结束时清理，避免线程复用导致身份串号。</p>
 */
public final class StarterContext {

    private static final ThreadLocal<KaiwuContext> CURRENT = new ThreadLocal<>();

    private StarterContext() {}

    public static KaiwuContext current() {
        return CURRENT.get();
    }

    public static KaiwuContext require() {
        KaiwuContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("当前请求不存在有效的 Kaiwu Context");
        }
        return context;
    }

    public static String userId() {
        KaiwuContext context = CURRENT.get();
        return context == null ? null : context.userId();
    }

    public static String projectId() {
        KaiwuContext context = CURRENT.get();
        return context == null ? null : context.projectId();
    }

    static void set(KaiwuContext context) {
        CURRENT.set(context);
    }

    static void clear() {
        CURRENT.remove();
    }
}
