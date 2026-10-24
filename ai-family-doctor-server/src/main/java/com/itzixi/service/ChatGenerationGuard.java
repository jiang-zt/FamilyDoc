package com.itzixi.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例下按登录用户名的原子生成占用锁。
 * 同步/流式/清空共用同一把锁：同一用户同一时刻只能有一个生成任务；
 * 不同用户 key 不同，天然并行。
 * putIfAbsent 是 CAS 原子操作：返回 null 说明 key 不存在、占用成功；返回已有值说明已占用。
 */
@Component
public class ChatGenerationGuard {

    private final ConcurrentHashMap<String, Boolean> generating = new ConcurrentHashMap<>();

    /** 尝试占用：成功返回 true；已被占用返回 false */
    public boolean tryAcquire(String userName) {
        return generating.putIfAbsent(userName, Boolean.TRUE) == null;
    }

    /** 释放占用（务必在 finally 中调用，覆盖模型异常/DB 异常等退出路径） */
    public void release(String userName) {
        generating.remove(userName);
    }
}
