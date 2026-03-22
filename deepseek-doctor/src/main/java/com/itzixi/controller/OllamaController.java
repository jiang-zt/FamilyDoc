package com.itzixi.controller;

import com.itzixi.bean.ChatEntity;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.OllamaService;
import com.itzixi.utils.AuthHelper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * @ClassName OllamaController
 * @Author jzt
 * @Version 1.0
 * @Description OllamaController
 **/
@Slf4j
@RestController
@RequestMapping("ollama")
public class OllamaController {

    @Resource
    private OllamaService ollamaService;

    @Resource
    private ChatRecordService chatRecordService;

    @Resource
    private AuthHelper authHelper;

    @PostMapping("/chat")
    public String chat(@RequestBody ChatEntity chatEntity, HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String message = chatEntity.getMessage();
        if (message == null || message.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }
        return ollamaService.chat(message.trim());
    }

    @PostMapping("/chat/stream")
    public void chatStream(@RequestBody ChatEntity chatEntity, HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String message = chatEntity.getMessage();
        if (message == null || message.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }
        ollamaService.chatStream(authUser, message.trim());
    }

    @GetMapping("/records")
    public Object getRecords(HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return chatRecordService.getChatRecordList(authUser);
    }

    @DeleteMapping("/records")
    public Object deleteRecords(HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return chatRecordService.deleteChatRecords(authUser);
    }
}
