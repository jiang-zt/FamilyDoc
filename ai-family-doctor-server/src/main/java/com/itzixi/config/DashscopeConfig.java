package com.itzixi.config;

import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DashscopeConfig {

    @Value("${dashscope.base-url}")
    private String baseUrl;

    @PostConstruct
    public void init() {
        Constants.baseHttpApiUrl = baseUrl;
    }
}
