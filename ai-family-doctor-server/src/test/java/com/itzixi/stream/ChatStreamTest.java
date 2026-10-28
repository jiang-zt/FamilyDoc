package com.itzixi.stream;
import com.itzixi.service.impl.ChatServiceImpl;
import com.itzixi.service.impl.ChatMetricRecorder;
import com.itzixi.context.PreparedChat;
import com.itzixi.utils.DashscopeChatClient;
import com.itzixi.utils.PromptLoader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChatStreamTest {
    final DashscopeChatClient client = mock(DashscopeChatClient.class);
    final ChatMetricRecorder recorder = mock(ChatMetricRecorder.class);
    final ChatServiceImpl service = new ChatServiceImpl();
    final RecordingEmitter emitter = new RecordingEmitter();
    final AtomicBoolean released = new AtomicBoolean();
    final ChatStreamSession session = new ChatStreamSession(emitter, () -> released.set(true));
    final PreparedChat input = new PreparedChat("hi",List.of());
    ChatStreamTest() {
        ReflectionTestUtils.setField(service,"dashscopeChatClient",client);
        ReflectionTestUtils.setField(service,"metricRecorder",recorder);
        PromptLoader prompt=mock(PromptLoader.class); when(prompt.getSystemPrompt()).thenReturn("system");
        ReflectionTestUtils.setField(service,"promptLoader",prompt);
        ReflectionTestUtils.setField(service,"streamTimeoutMs",1000L);
    }
    @Test void successEmitsDoneAndKeepsWhitespace() {
        when(client.stream(any())).thenReturn(Flux.just("你好"," ","世界"));
        service.chatStream("alice",input,session);
        assertThat(emitter.wire()).contains("event:delta","event:done").doesNotContain("event:failed");
        verify(recorder).record(argThat(m -> "SUCCESS".equals(m.getStatus()) && m.getRequestId().equals(session.requestId())),eq(List.of()),eq("你好 世界"));
        assertThat(released).isTrue(); assertThat(emitter.completed).isTrue();
    }
    @Test void emptyAnswerFailsAndRecordsQuestion() {
        when(client.stream(any())).thenReturn(Flux.empty());
        service.chatStream("alice",input,session);
        assertThat(emitter.payloads.toString()).contains("EMPTY_RESPONSE");
        verify(recorder).record(argThat(m -> "FAILED".equals(m.getStatus()) && "hi".equals(m.getQuestion())),eq(List.of()),eq(""));
    }
    @Test void modelFailureDoesNotExposeSecretAndRecordsFailure() {
        when(client.stream(any())).thenReturn(Flux.error(new IllegalStateException("secret")));
        service.chatStream("alice",input,session);
        assertThat(emitter.payloads.toString()).contains("MODEL_ERROR").doesNotContain("secret");
        verify(recorder).record(argThat(m -> "FAILED".equals(m.getStatus())),eq(List.of()),eq(""));
        assertThat(released).isTrue();
    }
    @Test void cancelledPartialAnswerIsRecordedWithoutSuccess() {
        when(client.stream(any())).thenReturn(Flux.just("partial").doOnNext(v -> session.cancel()));
        service.chatStream("alice",input,session);
        assertThat(emitter.wire()).doesNotContain("event:done");
        verify(recorder).record(argThat(m -> "CANCELLED".equals(m.getStatus())),eq(List.of()),anyString());
        assertThat(released).isTrue();
    }
    @Test void sendFailureCancelsAndReleases() {
        emitter.disconnect=true;
        when(client.stream(any())).thenReturn(Flux.just("answer"));
        service.chatStream("alice",input,session);
        verify(recorder).record(argThat(m -> "CANCELLED".equals(m.getStatus())),eq(List.of()),eq("answer"));
        assertThat(released).isTrue();
    }
    @Test void failedNotificationDisconnectDoesNotEraseModelFailure() {
        SseEmitter broken = new SseEmitter() {
            @Override public void send(SseEventBuilder builder) throws IOException { throw new IOException("disconnect"); }
        };
        var active=new ChatStreamSession(broken,()->{});
        when(client.stream(any())).thenReturn(Flux.error(new IllegalStateException("model")));
        service.chatStream("alice",input,active);
        verify(recorder).record(argThat(m -> "FAILED".equals(m.getStatus()) && "MODEL_ERROR".equals(m.getErrorCode())),eq(List.of()),eq(""));
    }
    @Test void blockedWriteDoesNotBlockCancellation() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var unblock = new java.util.concurrent.CountDownLatch(1);
        SseEmitter blocked = new SseEmitter() {
            @Override public void send(SseEventBuilder builder) {
                entered.countDown();
                try { unblock.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        ChatStreamSession active = new ChatStreamSession(blocked, () -> {});
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var writing = executor.submit(() -> active.delta("text"));
            try {
                assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                executor.submit(active::cancel).get(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                assertThat(active.isCancelled()).isTrue();
            } finally { unblock.countDown(); }
            writing.get(1, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Test void modelTimeoutReleasesGuardBeforeAttemptingBlockedFailureWrite() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var unblock = new java.util.concurrent.CountDownLatch(1);
        var freed = new java.util.concurrent.CountDownLatch(1);
        Object writeLock = new Object();
        SseEmitter blocked = new SseEmitter() {
            @Override public void send(SseEventBuilder builder) {
                synchronized (writeLock) {
                    entered.countDown();
                    boolean waiting = true;
                    while (waiting) {
                        try { unblock.await(); waiting = false; }
                        catch (InterruptedException ignored) { /* 模拟不可中断的 servlet 写出 */ }
                    }
                }
            }
        };
        ChatStreamSession active = new ChatStreamSession(blocked, freed::countDown);
        ReflectionTestUtils.setField(service, "streamTimeoutMs", 200L);
        when(client.stream(any())).thenReturn(Flux.just("answer")
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var work = executor.submit(() -> service.chatStream("alice", new com.itzixi.context.PreparedChat("hi",List.of()), active));
            try {
                assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(freed.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                verify(recorder,timeout(500)).record(argThat(m -> "TIMEOUT".equals(m.getStatus())),eq(List.of()),anyString());
            } finally { unblock.countDown(); }
            work.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(recorder).record(argThat(m -> "TIMEOUT".equals(m.getStatus())),eq(List.of()),anyString());
    }
    static class RecordingEmitter extends SseEmitter {
        final List<String> frames = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();
        boolean completed, disconnect;
        @Override public void send(SseEventBuilder builder) throws IOException {
            if (disconnect) throw new IOException("disconnected");
            for (var part : builder.build()) {
                if (part.getData() instanceof String text) frames.add(text);
                else payloads.add(part.getData());
            }
        }
        @Override public void complete() { completed = true; }
        String wire() { return String.join("", frames); }
    }
}
