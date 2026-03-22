package com.itzixi.service;

public interface OllamaService {

    String chat(String msg);

    void chatStream(String userName, String message);

}
