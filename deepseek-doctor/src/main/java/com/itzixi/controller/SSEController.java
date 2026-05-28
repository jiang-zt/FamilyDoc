package com.itzixi.controller;

import com.itzixi.utils.AuthHelper;
import com.itzixi.utils.SSEMsgType;
import com.itzixi.utils.SSEServer;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @ClassName SSEController
 * @Author 风间影月
 * @Version 1.0
 * @Description SSEController
 **/
//用于接收客户端连接SSE的请求
@Slf4j
@RestController
@RequestMapping("sse")
public class SSEController {

    @Resource
    private AuthHelper authHelper;

    /**
     * @Description: 连接sse服务的接口
     * @Author 
     * @param userId
     * @return SseEmitter
     */
    //当客户端访问 GET /connect?userId={xxx}
    //方法返回 SseEmitter 对象后，服务器会保持连接开放（HTTP 长连接），持续推送事件到客户端
    //MediaType.TEXT_EVENT_STREAM_VALUE 声明返回 SSE 数据流 封装在header中
    @GetMapping(path = "connect", produces = {MediaType.TEXT_EVENT_STREAM_VALUE})
    public SseEmitter connect(@RequestParam String userId, HttpServletRequest request) {
        String username = authHelper.requireUsername(request);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!userId.equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "用户不匹配");
        }
        return SSEServer.connect(userId);
    }

    /**
     * @Description: 发送单条消息给SSE的客户端
     * @Author
     * @param userId
     * @param message
     * @return Object
     */
    @GetMapping("sendMessage")
    public Object sendMessage(@RequestParam String userId, @RequestParam String message) {
        SSEServer.sendMessage(userId, message, SSEMsgType.MESSAGE);
        return "OK";
    }

    /**
     * @Description: 发送消息给所有客户端用户
     * @Author 风间影月
     * @param message
     * @return Object
     */
    @GetMapping("sendMessageAll")
    public Object sendMessageAll(@RequestParam String message) {
        SSEServer.sendMessageToAllUsers(message);
        return "OK";
    }

    /**
     * @Description: add事件流式输出
     * @Author 风间影月
     * @param userId
     * @param message
     * @return Object
     */
    @GetMapping("sendMessageAdd")
    public Object sendMessageAdd(@RequestParam String userId,
                                 @RequestParam String message) throws Exception {
        for (int i = 0 ; i < 10 ; i ++) {
            Thread.sleep(200);
            SSEServer.sendMessage(userId, message + "-" + i, SSEMsgType.ADD);
        }
        return "OK";
    }

    /**
     * @Description: 停止sse
     * @Author 风间影月
     * @param userId
     * @return Object
     */
    @GetMapping("stop")
    public Object stopServer(@RequestParam String userId)  {
        SSEServer.stopServer(userId);
        return "OK";
    }
    /**
     * @Description: 获得当前所有的会话总连接数（在线人数）
     * @Author 风间影月
     * @param
     * @return Object
     */
    @GetMapping("getOnlineCounts")
    public Object getOnlineCounts() {
        return SSEServer.getOnlineCounts();
    }


}
