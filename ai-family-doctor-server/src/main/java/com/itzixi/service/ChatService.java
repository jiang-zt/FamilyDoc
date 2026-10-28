package com.itzixi.service;
import com.itzixi.context.PreparedChat;
import com.itzixi.stream.ChatStreamSession;
public interface ChatService {
    String chat(String userName, PreparedChat input);
    void chatStream(String userName, PreparedChat input, ChatStreamSession session);
}
