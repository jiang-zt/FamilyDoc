package com.itzixi.controller;

import com.itzixi.bean.ChatEntity;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.OllamaService;
import com.itzixi.utils.AuthHelper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * @ClassName HelloController
 * @Author 风间影月
 * @Version 1.0
 * @Description HelloController
 **/
@Slf4j
@RestController
@RequestMapping("ollama")
public class OllamaController {

//    http://127.0.0.1:8080/ollama/ai/chat
//    http://150.109.247.64:9090/ollama/ai/chat?msg=你是谁？

    @Resource
    private OllamaService ollamaService;

    @Resource
    private ChatRecordService chatRecordService;

    @Resource
    private AuthHelper authHelper;

    @GetMapping("/ai/chat")
    public Object aiOllamaChat(@RequestParam String msg) {
        return ollamaService.aiOllamaChat(msg);
    }
    //json格式 仍然直接返回所有值 没有卡顿
    @GetMapping("/ai/stream1")
    public Flux<ChatResponse> aiOllamaStream1(@RequestParam String msg) {
        return ollamaService.aiOllamaStream1(msg);
    }

    @GetMapping("/ai/stream2")
    public List<String> aiOllamaStream2(@RequestParam String msg) {
        return ollamaService.aiOllamaStream2(msg);
    }//要结合SSE流式输出


    @GetMapping("/ai/v2/chat")
    public Object aiOllamaChatV2(@RequestParam String msg) {
        return ollamaService.aiOllamaChat(msg);
    }

    @GetMapping("/ai/v2/stream1")
    public Flux<ChatResponse> aiOllamaStream1V2(@RequestParam String msg) {
        return ollamaService.aiOllamaStream1(msg);
    }

    @GetMapping("/ai/v2/stream2")
    public List<String> aiOllamaStream2V2(@RequestParam String msg) {
        return ollamaService.aiOllamaStream2(msg);
    }

    /**
     * 有交流实体的获取返回信息
     * SpringBoot序列化与反序列化机制
     * @param chatEntity
     */
    @PostMapping("/ai/v3/doctor/stream")
    public void aiOllamaV3DoctorStream(@RequestBody ChatEntity chatEntity, HttpServletRequest request) {

        log.info(chatEntity.toString());
        String userName = chatEntity.getCurrentUserName();
        String message = chatEntity.getMessage();

        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!authUser.equals(userName)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "用户不匹配");
        }

        ollamaService.doDoctorStreamV3(userName, message);
    }

    /**
     * 获取聊天记录
     * @param who
     * @return
     */
    @GetMapping("/getRecords")
    public Object aiOllamaV3DoctorStream(@RequestParam String who, HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!authUser.equals(who)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "用户不匹配");
        }
        return chatRecordService.getChatRecordList(who);
    }

    @DeleteMapping("/deleteRecords")
    public Object deleteRecords(@RequestParam String who, HttpServletRequest request) {
        String authUser = authHelper.requireUsername(request);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!authUser.equals(who)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "用户不匹配");
        }
        int deleted = chatRecordService.deleteChatRecords(who);
        return deleted;
    }




//    线上 openai 需要配置秘钥，各位同学可以自行配置后，方可支持上下文历史记忆功能，原理是使用SpringAI的Advisor，核心原理是AOP
//    @Resource
//    private ChatClient chatClient;
//    private InMemoryChatMemory chatMemory = new InMemoryChatMemory();
//
//    @PostMapping("/ai/v4/doctor/stream")
//    public Flux<String> aiOllamaV4DoctorStream(@RequestBody ChatEntity chatEntity) {
//
//        log.info(chatEntity.toString());
//        String userName = chatEntity.getCurrentUserName();
//        String message = chatEntity.getMessage();
//
//        Prompt prompt = new Prompt(new UserMessage(message));
//        Flux<String> streamResponse = chatClient.prompt(prompt)
//                .advisors(new MessageChatMemoryAdvisor(chatMemory, userName, 250))
//                .stream().content();
//        return streamResponse;
//    }

}
