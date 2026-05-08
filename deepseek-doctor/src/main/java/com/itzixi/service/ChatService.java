package com.itzixi.service;

public interface ChatService {

    String chat(String userName, String msg);

    void chatStream(String userName, String message);

}
