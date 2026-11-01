package com.itzixi.security;

import com.itzixi.utils.AuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import java.time.Clock;
import java.util.Locale;

@Component
public class PublicProtection implements HandlerInterceptor {
    private final RequestQuota quota = new RequestQuota(Clock.systemUTC(), 20000);
    private final Environment env;
    private final AuthHelper auth;
    public PublicProtection(Environment env, AuthHelper auth) { this.env = env; this.auth = auth; }
    private void check(String kind, String key, int fallback, long seconds) {
        int limit = env.getProperty("protection." + kind, Integer.class, fallback);
        long retry = quota.take(kind + ":" + key, limit, seconds);
        if (retry > 0) {
            throw new QuotaExceeded(retry);
        }
    }
    public void checkLoginAccount(String username) {
        // 限制全部登录尝试，避免并行请求穿透“失败后计数”。成功登录也计数。
        check("login-account-per-minute", username.toLowerCase(Locale.ROOT), 10, 60);
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) return true;
        if (!(handler instanceof org.springframework.web.method.HandlerMethod method)) return true;
        Class<?> controller = method.getBeanType();
        String action = method.getMethod().getName();
        String ip = request.getRemoteAddr(); // 生产环境仅接受同机 Nginx；由可信代理写入来源地址。
        if (com.itzixi.controller.AuthController.class.isAssignableFrom(controller) && action.equals("register")) {
            check("register-global-per-hour", "all", 100, 3600);
            check("register-ip-per-hour", ip, 5, 3600);
        } else if (com.itzixi.controller.AuthController.class.isAssignableFrom(controller) && action.equals("login")) {
            check("login-ip-per-minute", ip, 30, 60);
        } else if (com.itzixi.controller.ChatController.class.isAssignableFrom(controller)
                && (action.equals("chat") || action.equals("chatStream"))) {
            String username = auth.requireUsername(request);
            if (username == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
            check("chat-ip-per-minute", ip, 60, 60);
            check("chat-user-per-minute", username, 10, 60);
            check("chat-user-per-day", username, 100, 86400);
            check("chat-global-per-day", "all", 1000, 86400);
        }
        return true;
    }
    public static class QuotaExceeded extends ResponseStatusException {
        private final long retry;
        QuotaExceeded(long retry) { super(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁或额度已用尽，请稍后再试"); this.retry = retry; }
        @Override public HttpHeaders getHeaders() {
            var headers = new HttpHeaders(); headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retry)); return headers;
        }
    }
}
