package com.itzixi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;
/*
主线程（Tomcat 线程）拿到任务，立刻扔给这个 chatStreamExecutor 去调用大模型并流式返回，主线程立刻释放去接待下一个用户
这个线程池能同时接纳的最大任务数 = max (8) + capacity (16) = 24 个并发任务。超过 24 个就会触发拒绝策略
 */
@Configuration
@EnableAsync
public class ChatAsyncConfig {
    @Bean("chatStreamExecutor")
    public ThreadPoolTaskExecutor chatStreamExecutor(
            @Value("${chat.stream.core-pool-size:4}") int core,
            @Value("${chat.stream.max-pool-size:8}") int max,
            @Value("${chat.stream.queue-capacity:16}") int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix("chat-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
