package com.itzixi.stream;

import com.itzixi.config.ChatAsyncConfig;
import com.itzixi.service.ChatService;
import com.itzixi.service.ChatStreamWorker;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatAsyncTest {
    @Test void consumptionRunsOnDedicatedExecutorThroughSpringProxy() throws Exception {
        ChatService service = mock(ChatService.class);
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        doAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            called.countDown();
            return null;
        }).when(service).chatStream(eq("alice"), eq("hi"), isNull());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ChatService.class, () -> service);
            context.register(ChatAsyncConfig.class, ChatStreamWorker.class);
            context.refresh();
            context.getBean(ChatStreamWorker.class).generate("alice", "hi", null);
            assertThat(called.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("chat-stream-");
        }
    }
}
