package com.kaiwu.starter.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为 Servlet 目标服务建立统一、安全的请求摘要日志与 MDC 上下文。
 *
 * <p>只记录 method、path、状态和耗时等元数据；不读取请求/响应 body，也不记录 query、Cookie、
 * Authorization 或 Gateway Context。已验证身份由 Starter 拦截器写入 request attribute，避免把
 * 未受信的客户端 Header 当作身份日志。</p>
 */
public final class HttpAccessLogFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String USER_ID_ATTRIBUTE = HttpAccessLogFilter.class.getName() + ".userId";
    public static final String PROJECT_ID_ATTRIBUTE = HttpAccessLogFilter.class.getName() + ".projectId";

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("kaiwu.http.access");
    private static final String EMPTY = "-";
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_ADDRESS_LENGTH = 64;
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\r\\n\\t]");
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final String serviceName;

    /**
     * 创建目标服务访问日志过滤器。
     *
     * @param serviceName 稳定的 Spring 应用名
     */
    public HttpAccessLogFilter(String serviceName) {
        this.serviceName = safe(serviceName, 128);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String traceId = traceId(request.getHeader(TRACE_HEADER));
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        request.setAttribute(TRACE_HEADER, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        MDC.put("traceId", traceId);
        MDC.put("service", serviceName);
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
            ACCESS_LOG.info(
                    "http_request service={} traceId={} method={} path={} routeId={} userId={} "
                            + "projectId={} status={} durationMs={} clientIp={}",
                    serviceName,
                    traceId,
                    safe(request.getMethod(), 16),
                    safe(request.getRequestURI(), MAX_PATH_LENGTH),
                    EMPTY,
                    attribute(request, USER_ID_ATTRIBUTE),
                    attribute(request, PROJECT_ID_ATTRIBUTE),
                    status,
                    durationMs,
                    clientIp(request));
            restore(previousMdc);
        }
    }

    private static String traceId(String incoming) {
        if (incoming != null && TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0];
        return safe(value, MAX_ADDRESS_LENGTH);
    }

    private static String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return safe(value == null ? null : value.toString(), 64);
    }

    private static String safe(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return EMPTY;
        }
        String normalized = CONTROL_CHARACTERS.matcher(value).replaceAll("_");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static void restore(Map<String, String> previousMdc) {
        MDC.clear();
        if (previousMdc != null) {
            MDC.setContextMap(previousMdc);
        }
    }
}
