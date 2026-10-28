package com.itzixi.controller;

import com.itzixi.bean.ChatEntity;
import com.itzixi.context.ChatContextNormalizer;
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
    private ChatContextNormalizer contextNormalizer;

    @Resource
    private ChatStreamWorker chatStreamWorker;

    @Value("${chat.stream.emitter-timeout-ms:180000}")//3min
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
        return chatService.chat(authUser, contextNormalizer.prepare(chatEntity));
    }
    //手动设置 SSE 相关的 header 或关闭缓冲需要response
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatEntity chatEntity, HttpServletRequest request, HttpServletResponse response) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");//401
        }
        var input = contextNormalizer.prepare(chatEntity);
        //统一采用用户名称记录
        if (!chatGenerationGuard.tryAcquire(authUser)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有生成任务进行中，请稍候");
        }
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);//创建连接设置超时时间
        ChatStreamSession session = new ChatStreamSession(emitter, () -> chatGenerationGuard.release(authUser));//封装会话+回调函数
        try {
            chatStreamWorker.generate(authUser, input, session);//提交异步任务
        } catch (TaskRejectedException ex) {//队列满，任务直接拒绝
            session.cancel();
            session.close();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务繁忙，请稍后重试");
        } catch (RuntimeException ex) {
            session.cancel();
            session.close();
            throw ex;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");//响应不被缓存
        response.setHeader("X-Accel-Buffering", "no");//控制 Nginx 的反向代理缓冲行为
        return emitter;//response.body:SseEmitter（写数据 + 控制 complete/超时）
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
