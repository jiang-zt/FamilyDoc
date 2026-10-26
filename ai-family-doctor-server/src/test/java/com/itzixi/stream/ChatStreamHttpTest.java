package com.itzixi.stream;

import com.itzixi.controller.ChatController;
import com.itzixi.service.ChatGenerationGuard;
import com.itzixi.service.ChatStreamWorker;
import com.itzixi.utils.AuthHelper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatStreamHttpTest {
    final ChatGenerationGuard guard = new ChatGenerationGuard();
    final ChatStreamWorker worker = mock(ChatStreamWorker.class);
    final AuthHelper auth = mock(AuthHelper.class);
    final MockMvc mvc;
    ChatStreamHttpTest() {
        ChatController controller = new ChatController();
        ReflectionTestUtils.setField(controller, "chatGenerationGuard", guard);
        ReflectionTestUtils.setField(controller, "chatStreamWorker", worker);
        ReflectionTestUtils.setField(controller, "authHelper", auth);
        ReflectionTestUtils.setField(controller, "emitterTimeoutMs", 3000L);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(auth.requireUsername(any())).thenReturn("alice");
    }
    @Test void returnsRequestScopedJsonEventsAndCompletesResponse() throws Exception {
        var initial = mvc.perform(post("/chat/stream").contentType("application/json")
                .content("{\"message\":\"hi\"}")).andExpect(request().asyncStarted()).andReturn();
        assertThat(guard.tryAcquire("alice")).isFalse();
        var captor = ArgumentCaptor.forClass(ChatStreamSession.class);
        verify(worker).generate(eq("alice"), eq("hi"), captor.capture());
        var session = captor.getValue();
        session.delta("你好\n世界");
        session.saveAndDone(() -> {});
        session.close();
        var result = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream")).andReturn();
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("event:delta", "event:done", "\\n", "requestId").doesNotContain("event:failed");
        assertThat(guard.tryAcquire("alice")).isTrue();
        guard.release("alice");
    }
    @Test void rejectedTaskReturns503AndReleasesGuard() throws Exception {
        doThrow(new TaskRejectedException("full")).when(worker).generate(any(), any(), any());
        mvc.perform(post("/chat/stream").contentType("application/json").content("{\"message\":\"hi\"}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(guard.tryAcquire("alice")).isTrue();
    }
    @Test void overlappingRequestsAreRejectedBeforeStreaming() throws Exception {
        guard.tryAcquire("alice");
        mvc.perform(post("/chat/stream").contentType("application/json").content("{\"message\":\"hi\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(worker);
    }
    @Test void invalidInputAndAnonymousRequestsNeverSubmitWork() throws Exception {
        mvc.perform(post("/chat/stream").contentType("application/json").content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
        when(auth.requireUsername(any())).thenReturn(null);
        mvc.perform(post("/chat/stream").contentType("application/json").content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(worker);
    }
}
