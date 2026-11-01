package com.itzixi.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ProtectionConfig implements WebMvcConfigurer {
    private final PublicProtection protection;
    public ProtectionConfig(PublicProtection protection) { this.protection = protection; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(protection).addPathPatterns("/auth/**", "/chat", "/chat/**");
    }
}
