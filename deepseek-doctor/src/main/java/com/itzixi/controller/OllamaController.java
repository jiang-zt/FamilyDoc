package com.itzixi.controller;

import com.itzixi.bean.ChatEntity;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.OllamaService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

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

    //import org.springframework.ai.ollama.OllamaChatClient;
    @Resource
    private OllamaChatClient ollamaChatClient;

    @Resource
    private OllamaService ollamaService;

    @Resource
    private ChatRecordService chatRecordService;

    @GetMapping("/ai/chat")
    public Object aiOllamaChat(@RequestParam String msg) {
        // 同步调用deepseek，当前页面会卡住，直到获得所有的数据才会返回给页面
        return ollamaChatClient.call(msg);
    }
    //json格式 仍然直接返回所有值 没有卡顿
    @GetMapping("/ai/stream1")
    public Flux<ChatResponse> aiOllamaStream1(@RequestParam String msg) {
        Prompt prompt = new Prompt(new UserMessage(msg));
        Flux<ChatResponse> streamResponse = ollamaChatClient.stream(prompt);
        return streamResponse;
    }

    @GetMapping("/ai/stream2")
    public List<String> aiOllamaStream2(@RequestParam String msg) {
        Prompt prompt = new Prompt(new UserMessage(msg));
        Flux<ChatResponse> streamResponse = ollamaChatClient.stream(prompt);

        List<String> list = streamResponse.toStream().map(chatResponse -> {
            String content = chatResponse.getResult().getOutput().getContent();
//            System.out.println(content);
            log.info(content);
            return content;//返回收集到的结果集合 用集合接收
        }).collect(Collectors.toList());
        //将返回的content添加到list再返回
        //streamResponse.toStream() 是JDK8的特性
        return list;//手动流式返回
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
    public void aiOllamaV3DoctorStream(@RequestBody ChatEntity chatEntity) {

        log.info(chatEntity.toString());
        String userName = chatEntity.getCurrentUserName();
        String message = chatEntity.getMessage();

        ollamaService.doDoctorStreamV3(userName, message);
    }

    /**
     * 获取聊天记录
     * @param who
     * @return
     */
    @GetMapping("/getRecords")
    public Object aiOllamaV3DoctorStream(@RequestParam String who) {
        return chatRecordService.getChatRecordList(who);
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
