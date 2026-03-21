//非流式返回：ollamaChatClient.call();
//流式返回：ollamaChatClient.stream();
//将流式返回结果转为List<String>

 //获取返回信息流 
//阻塞式
    Flux<ChatResponse> streamResponse = ollamaChatClient.stream(prompt);
        //提取信息，转为List<String>类型
        List<String> list = streamResponse.toStream().map(chatResponse -> {
            String content = chatResponse.getResult().getOutput().getContent();

            SSEServer.sendMessage(userName, content, SSEMsgType.ADD);
//向客户端主动推送结果
            log.info(content);
            return content;
        }).collect(Collectors.toList());

//非阻塞式


@Override
public void doDoctorStreamV3(String userName, String message) {
    // 1) 先存用户消息
    chatRecordService.saveChatRecord(userName, message, ChatTypeEnum.USER);

    Prompt prompt = new Prompt(new UserMessage(message));
    StringBuilder full = new StringBuilder(512);

    // 2) 非阻塞订阅：来一个 token 处理一个 token
    ollamaChatClient.stream(prompt)
        .map(resp -> resp.getResult().getOutput().getContent())
        // 你的SSE发送+MyBatis落库都是阻塞操作，切到boundedElastic更安全
        .publishOn(Schedulers.boundedElastic())
        .doOnNext(chunk -> {
            full.append(chunk);
            SSEServer.sendMessage(userName, chunk, SSEMsgType.ADD);
        })
        .doOnComplete(() -> {
            SSEServer.sendMessage(userName, "GG", SSEMsgType.FINISH);
            chatRecordService.saveChatRecord(userName, full.toString(), ChatTypeEnum.BOT);
        })
        .doOnError(e -> {
            // 异常时也结束会话，避免前端一直等待
            SSEServer.sendMessage(userName, "服务异常，请稍后重试", SSEMsgType.FINISH);
        })
        .subscribe();
}

//用户消息先落库、AI消息后落库，这个顺序如果中途异常会有什么一致性风险？你怎么补偿？
