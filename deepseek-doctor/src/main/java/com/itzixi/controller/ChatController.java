package com.itzixi.controller;

import com.itzixi.bean.ChatEntity;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.ChatService;
import com.itzixi.utils.AuthHelper;//聊天前置统一鉴权
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
//接口层
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
    private ChatService chatService;

    @Resource
    private ChatRecordService chatRecordService;

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

    @PostMapping("/stream")//流式返回
    public void chatStream(@RequestBody ChatEntity chatEntity, HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");//401
        }
        String message = chatEntity.getMessage();
        if (message == null || message.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }
        //统一采用用户名称记录
        chatService.chatStream(authUser, message.trim());
    }

    @GetMapping("/records")//获取聊天记录
    public Object getRecords(HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return chatRecordService.getChatRecordList(authUser);
    }

    @DeleteMapping("/records")//删除记录
    public Object deleteRecords(HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return chatRecordService.deleteChatRecords(authUser);
    }
}
