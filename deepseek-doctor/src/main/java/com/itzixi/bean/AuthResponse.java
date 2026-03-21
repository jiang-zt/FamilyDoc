package com.itzixi.bean;

import lombok.Data;

@Data
public class AuthResponse {
    private String token;
    private AuthUserView user;
    private Long expiresAt;
}
