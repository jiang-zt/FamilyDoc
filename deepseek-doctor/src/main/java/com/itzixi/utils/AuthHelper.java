package com.itzixi.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AuthHelper {

    private final JwtUtil jwtUtil;

    public AuthHelper(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    public String extractToken(HttpServletRequest request) {
        //从请求头中获取Authorization字段标准方式
        String authHeader = request.getHeader("Authorization");
        //如果Authorization字段存在且以Bearer开头
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        //从请求头中获取headerUserToken字段 自定义方式
        String headerToken = request.getHeader("headerUserToken");
        //如果headerUserToken字段存在
        if (StringUtils.hasText(headerToken)) {
            return headerToken;
        }

        //从请求参数中获取token字段
        String queryToken = request.getParameter("token");
        //如果token字段存在
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }

        return null;
    }

    public String requireUsername(HttpServletRequest request) {
        String token = extractToken(request);
        //如果token不存在或token无效
        if (!StringUtils.hasText(token) || !jwtUtil.isValid(token)) {
            return null;
        }
        return jwtUtil.getUsername(token);
    }
}
