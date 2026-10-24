package com.itzixi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ModelStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(ModelStartupLogger.class);

    @Value("${dashscope.base-url}")
    private String baseUrl;

    @Value("${dashscope.model}")
    private String model;

    @Value("${dashscope.mock.enabled:false}")
    private boolean mockEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void logModelInfo() {
        log.info("【应用启动完成】 model={} baseUrl={} mockEnabled={}", model, baseUrl, mockEnabled);
    }
}
