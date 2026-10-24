package com.itzixi;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @ClassName CorsConfig
 * @Author 
 * @Version 1.0
 * @Description CorsConfig
 **/

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    //添加跨域的映射
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")//添加所有路径 全部开放，例如 /sse/connect、/chat...
                .allowedOriginPatterns("*")//允许任意来源（任意协议+域名+端口）
                .allowedMethods("*")//允许所有请求方法
                .allowedHeaders("*")//允许所有请求头 Content-Type、Authorization、自定义 token 头
                .allowCredentials(true)//允许浏览器携带凭证（Cookie、HTTP 认证、TLS 客户端证书等）
                .maxAge(60 * 60);//预检请求结果的缓存时间
    }
}
