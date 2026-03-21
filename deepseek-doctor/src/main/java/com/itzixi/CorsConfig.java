package com.itzixi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @ClassName CorsConfig
 * @Author 风间影月
 * @Version 1.0
 * @Description CorsConfig
 **/
//跨域解决类 是一个配置文件 加上注解@Configuration
@Configuration
public class CorsConfig implements WebMvcConfigurer {
//    150.109.247.64
    @Value("${website.domain}")
    private String domain;//前端的域

    //添加跨域的映射
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")//添加所有路径 全部开放例如/connect、/ollama...
                .allowedOrigins(domain)
//                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(60 * 60);
    }
}
