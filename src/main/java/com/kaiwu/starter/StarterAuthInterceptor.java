package com.kaiwu.starter;

import com.kaiwu.starter.logging.HttpAccessLogFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 验证 Gateway Context 并执行声明式权限。
 */
public final class StarterAuthInterceptor implements HandlerInterceptor {

    public static final String CONTEXT_HEADER = "X-Kaiwu-Context";

    private final GatewayContextVerifier verifier;
    private final PermissionResolver permissionResolver;

    public StarterAuthInterceptor(GatewayContextVerifier verifier, PermissionResolver permissionResolver) {
        this.verifier = verifier;
        this.permissionResolver = permissionResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        try {
            KaiwuContext context = verifier.verify(request.getHeader(CONTEXT_HEADER));
            String requiredPermission = requiredPermission(handler);
            if (requiredPermission != null && !permissionResolver.hasPermission(context, requiredPermission)) {
                return reject(response, HttpServletResponse.SC_FORBIDDEN, "无操作权限", "starter.permissionDenied");
            }
            StarterContext.set(context);
            request.setAttribute(HttpAccessLogFilter.USER_ID_ATTRIBUTE, context.userId());
            request.setAttribute(HttpAccessLogFilter.PROJECT_ID_ATTRIBUTE, context.projectId());
            putMdc("userId", context.userId());
            putMdc("projectId", context.projectId());
            return true;
        } catch (ContextVerificationException exception) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未认证或认证已过期", "starter.authenticationRequired");
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        StarterContext.clear();
        MDC.remove("userId");
        MDC.remove("projectId");
    }

    private static void putMdc(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private static String requiredPermission(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        RequirePermission annotation = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        }
        return annotation == null ? null : annotation.value();
    }

    private static boolean reject(HttpServletResponse response, int status, String message, String messageKey)
            throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
                .write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"messageKey\":\"" + messageKey
                        + "\",\"data\":null}");
        return false;
    }
}
