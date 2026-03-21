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
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        String headerToken = request.getHeader("headerUserToken");
        if (StringUtils.hasText(headerToken)) {
            return headerToken;
        }

        String queryToken = request.getParameter("token");
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }

        return null;
    }

    public String requireUsername(HttpServletRequest request) {
        String token = extractToken(request);
        if (!StringUtils.hasText(token) || !jwtUtil.isValid(token)) {
            return null;
        }
        return jwtUtil.getUsername(token);
    }
}
