package com.itzixi.config;

import com.itzixi.utils.AuthHelper;
import com.itzixi.utils.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceMdcFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID_KEY = "traceId";
    private static final String USER_ID_KEY = "userId";
    private static final String UNKNOWN_USER = "-";

    private final AuthHelper authHelper;
    private final JwtUtil jwtUtil;

    public TraceMdcFilter(AuthHelper authHelper, JwtUtil jwtUtil) {
        this.authHelper = authHelper;
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        String userId = resolveUserId(request);

        MDC.put(TRACE_ID_KEY, traceId);
        MDC.put(USER_ID_KEY, userId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
            MDC.remove(USER_ID_KEY);
        }
        /*
        因为 Web 容器线程是复用的，
        如果请求结束后不清理 MDC，下一个请求复用同一个线程时可能带上上一个请求的 traceId 或 userId，造成日志串线。
        所以必须在 finally 里清理。
         */
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incomingTraceId = request.getHeader(TRACE_ID_HEADER);
        if (StringUtils.hasText(incomingTraceId)) {
            return incomingTraceId.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String resolveUserId(HttpServletRequest request) {
        String token = authHelper.extractToken(request);
        if (StringUtils.hasText(token) && jwtUtil.isValid(token)) {
            String uid = jwtUtil.getUserId(token);
            if (StringUtils.hasText(uid)) {
                return uid;
            }
            String username = jwtUtil.getUsername(token);
            if (StringUtils.hasText(username)) {
                return username;
            }
        }

        String headerUserId = request.getHeader("headerUserId");
        if (StringUtils.hasText(headerUserId)) {
            return headerUserId.trim();
        }

        String queryUserId = request.getParameter("userId");
        if (StringUtils.hasText(queryUserId)) {
            return queryUserId.trim();
        }

        return UNKNOWN_USER;
    }
}
