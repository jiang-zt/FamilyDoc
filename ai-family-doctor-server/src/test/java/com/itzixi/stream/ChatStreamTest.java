package com.itzixi.stream;

import com.itzixi.service.impl.ChatServiceImpl;
import com.itzixi.service.impl.ChatTurnSaver;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.ChatMetricService;
import com.itzixi.scoring.MedicalAnswerScorer;
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
    final ChatTurnSaver saver = mock(ChatTurnSaver.class);
    final ChatServiceImpl service = new ChatServiceImpl();
    final RecordingEmitter emitter = new RecordingEmitter();
    final AtomicBoolean released = new AtomicBoolean();
    final ChatStreamSession session = new ChatStreamSession(emitter, () -> released.set(true));

    ChatStreamTest() {
        ReflectionTestUtils.setField(service, "dashscopeChatClient", client);
        ReflectionTestUtils.setField(service, "chatTurnSaver", saver);
        ReflectionTestUtils.setField(service, "chatRecordService", mock(ChatRecordService.class));
        ReflectionTestUtils.setField(service, "chatMetricService", mock(ChatMetricService.class));
        ReflectionTestUtils.setField(service, "medicalAnswerScorer", mock(MedicalAnswerScorer.class));
        ReflectionTestUtils.setField(service, "promptLoader", mock(PromptLoader.class));
        ReflectionTestUtils.setField(service, "streamTimeoutMs", 1000L);
        when(((PromptLoader) ReflectionTestUtils.getField(service, "promptLoader")).getSystemPrompt()).thenReturn("system");
    }

    @Test void savesBeforeDoneAndKeepsWhitespace() throws Exception {
        when(client.stream(any())).thenReturn(Flux.just("你好", " ", "世界"));
        doAnswer(invocation -> { assertThat(emitter.wire()).doesNotContain("event:done"); return null; })
            .when(saver).saveCompletedTurn("alice", "hi", "你好 世界");
        service.chatStream("alice", "hi", session);
        verify(saver).saveCompletedTurn("alice", "hi", "你好 世界");
        assertThat(emitter.wire()).contains("event:delta", "event:done").doesNotContain("event:failed");
        assertThat(emitter.completed).isTrue();
        assertThat(released).isTrue();
    }
    @Test void emptyAnswerFailsWithoutSaving() {
        when(client.stream(any())).thenReturn(Flux.empty());
        service.chatStream("alice", "hi", session);
        assertThat(emitter.payloads.toString()).contains("EMPTY_RESPONSE");
        assertThat(emitter.wire()).contains("event:failed").doesNotContain("event:done");
        verifyNoInteractions(saver);
        assertThat(released).isTrue();
    }
    @Test void saveFailureCannotBecomeDone() {
        when(client.stream(any())).thenReturn(Flux.just("answer"));
        doThrow(new IllegalStateException("database")).when(saver).saveCompletedTurn(any(), any(), any());
        service.chatStream("alice", "hi", session);
        assertThat(emitter.payloads.toString()).contains("SAVE_FAILED");
        assertThat(emitter.wire()).doesNotContain("event:done");
    }
    @Test void modelFailureClosesAndReleases() {
        when(client.stream(any())).thenReturn(Flux.error(new IllegalStateException("secret")));
        service.chatStream("alice", "hi", session);
        assertThat(emitter.payloads.toString()).contains("MODEL_ERROR").doesNotContain("secret");
        verifyNoInteractions(saver);
        assertThat(emitter.completed).isTrue();
        assertThat(released).isTrue();
    }
    @Test void cancellationStopsUpstreamAndDoesNotSave() {
        when(client.stream(any())).thenReturn(Flux.<String>never().doOnSubscribe(s -> session.cancel()));
        service.chatStream("alice", "hi", session);
        verifyNoInteractions(saver);
        assertThat(emitter.wire()).doesNotContain("event:done");
        assertThat(released).isTrue();
    }
    @Test void timeoutCancelsUpstreamAndFails() {
        ReflectionTestUtils.setField(service, "streamTimeoutMs", 30L);
        AtomicBoolean disposed = new AtomicBoolean();
        when(client.stream(any())).thenReturn(Flux.<String>never().doOnCancel(() -> disposed.set(true)));
        service.chatStream("alice", "hi", session);
        assertThat(disposed).isTrue();
        assertThat(emitter.payloads.toString()).contains("MODEL_TIMEOUT");
        verifyNoInteractions(saver);
        assertThat(released).isTrue();
    }
    @Test void sendFailureCancelsWithoutSaving() {
        emitter.disconnect = true;
        when(client.stream(any())).thenReturn(Flux.just("answer"));
        service.chatStream("alice", "hi", session);
        verifyNoInteractions(saver);
        assertThat(released).isTrue();
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
            var work = executor.submit(() -> service.chatStream("alice", "hi", active));
            try {
                assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(freed.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally { unblock.countDown(); }
            work.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        verifyNoInteractions(saver);
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
