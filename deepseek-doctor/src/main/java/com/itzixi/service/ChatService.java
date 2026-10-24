package com.itzixi.service;

public interface ChatService {

    String chat(String userName, String msg);//直接返回

    void chatStream(String userName, String message);//流式sse推送

}
