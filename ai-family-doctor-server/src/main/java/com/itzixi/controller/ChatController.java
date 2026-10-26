package com.itzixi.controller;

import com.itzixi.bean.ChatEntity;
import com.itzixi.service.ChatStreamWorker;
import com.itzixi.stream.ChatStreamSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.itzixi.service.ChatGenerationGuard;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.ChatService;
import com.itzixi.utils.AuthHelper;//聊天前置统一鉴权
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * @ClassName ChatController
 * @Author jzt
 * @Version 1.0
 * @Description ChatController
 **/
@Slf4j
@RestController
@RequestMapping("chat")//http://localhost:8080/chat
public class ChatController {

    @Resource
    private ChatStreamWorker chatStreamWorker;

    @Value("${chat.stream.emitter-timeout-ms:180000}")
    private long emitterTimeoutMs;

    @Resource
    private ChatService chatService;

    @Resource
    private ChatRecordService chatRecordService;

    @Resource
    private ChatGenerationGuard chatGenerationGuard;

    @Resource
    private AuthHelper authHelper;

    @PostMapping//直接返回
    public String chat(@RequestBody ChatEntity chatEntity, HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String message = chatEntity.getMessage();
        if (message == null || message.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");//400
        }
        return chatService.chat(authUser, message.trim());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)//流式返回
    public SseEmitter chatStream(@RequestBody ChatEntity chatEntity, HttpServletRequest request, HttpServletResponse response) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");//401
        }
        String message = chatEntity.getMessage();
        if (message == null || message.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }
        //统一采用用户名称记录
        if (!chatGenerationGuard.tryAcquire(authUser)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有生成任务进行中，请稍候");
        }
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);//创建连接
        ChatStreamSession session = new ChatStreamSession(emitter, () -> chatGenerationGuard.release(authUser));//封装会话
        try {
            chatStreamWorker.generate(authUser, message.trim(), session);//提交异步任务
        } catch (TaskRejectedException ex) {//队列满，任务直接拒绝
            session.cancel();
            session.close();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务繁忙，请稍后重试");
        } catch (RuntimeException ex) {
            session.cancel();
            session.close();
            throw ex;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return emitter;//返回浏览器解析流
    }

    @GetMapping("/records")//获取聊天记录
    public Object getRecords(HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return chatRecordService.getChatRecordList(authUser);
    }

    @DeleteMapping("/records")//删除某用户的所有记录（与生成共用同一把占用锁：生成中不允许清空，清空中不允许生成）
    public Object deleteRecords(HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!chatGenerationGuard.tryAcquire(authUser)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "正在生成中，请稍候再清空");
        }
        try {
            return chatRecordService.deleteChatRecords(authUser);
        } finally {
            chatGenerationGuard.release(authUser);
        }
    }
}
