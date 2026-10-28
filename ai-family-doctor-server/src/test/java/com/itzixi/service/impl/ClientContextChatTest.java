package com.itzixi.service.impl;
import com.itzixi.bean.ChatMetric;
import com.itzixi.context.PreparedChat;
import com.itzixi.service.ChatGenerationGuard;
import com.itzixi.stream.ChatStreamSession;
import com.itzixi.utils.DashscopeChatClient;
import com.itzixi.utils.PromptLoader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ClientContextChatTest {
    final ChatServiceImpl service = new ChatServiceImpl();
    final DashscopeChatClient client = mock(DashscopeChatClient.class);
    final ChatMetricRecorder recorder = mock(ChatMetricRecorder.class);
    ClientContextChatTest() {
        ReflectionTestUtils.setField(service,"dashscopeChatClient",client);
        ReflectionTestUtils.setField(service,"metricRecorder",recorder);
        ReflectionTestUtils.setField(service,"chatGenerationGuard",new ChatGenerationGuard());
        ReflectionTestUtils.setField(service,"streamTimeoutMs",1000L);
        PromptLoader prompt = mock(PromptLoader.class);
        when(prompt.getSystemPrompt()).thenReturn("server system");
        ReflectionTestUtils.setField(service,"promptLoader",prompt);
    }
    @Test void usesClientContextAndPersistsFullCurrentQuestion() {
        when(client.chat(any())).thenReturn("answer");
        var history=List.of(Map.of("role","user","content","previous"),Map.of("role","assistant","content","reply"));
        assertThat(service.chat("alice",new PreparedChat("  current  ",history))).isEqualTo("answer");
        var messages=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client).chat(messages.capture());
        assertThat(messages.getValue()).hasSize(4).contains(Map.of("role","user","content","  current  "));
        var metrics=org.mockito.ArgumentCaptor.forClass(ChatMetric.class);
        verify(recorder).record(metrics.capture(),eq(history),eq("answer"));
        assertThat(metrics.getValue().getQuestion()).isEqualTo("  current  ");
        assertThat(metrics.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(metrics.getValue().getContextMessageCount()).isEqualTo(2);
    }
    @Test void failedSyncStillRecordsQuestionWithoutScore() {
        when(client.chat(any())).thenThrow(new IllegalStateException("model"));
        assertThatThrownBy(() -> service.chat("alice",new PreparedChat("question",List.of()))).isInstanceOf(RuntimeException.class);
        verify(recorder).record(argThat(m -> m.getQuestion().equals("question") && m.getStatus().equals("FAILED")),eq(List.of()),eq(""));
    }
    @Test void cancellationAndTimeoutAlsoRecordQuestion() {
        var session = new ChatStreamSession(new SseEmitter(), () -> {}); session.cancel();
        service.chatStream("alice", new PreparedChat("cancelled question",List.of()), session);
        verify(recorder).record(argThat(m -> m.getStatus().equals("CANCELLED") && m.getQuestion().equals("cancelled question")),eq(List.of()),eq(""));
    }
}
