package com.itzixi.service;

import com.itzixi.stream.ChatStreamSession;
import com.itzixi.context.PreparedChat;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ChatStreamWorker {
    private final ChatService chatService;
    public ChatStreamWorker(ChatService chatService) { this.chatService = chatService; }

    @Async("chatStreamExecutor")//多线程池异步推流
    public void generate(String userName, PreparedChat input, ChatStreamSession session) {
        chatService.chatStream(userName, input, session);
    }
}
