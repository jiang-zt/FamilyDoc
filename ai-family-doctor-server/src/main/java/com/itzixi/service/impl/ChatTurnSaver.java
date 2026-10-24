package com.itzixi.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itzixi.service.ChatRecordService;
import com.itzixi.utils.ChatTypeEnum;

import jakarta.annotation.Resource;

//保存用户+AI回复的一组消息
//短事务落完整问答
@Service 
public class ChatTurnSaver {
    @Resource private ChatRecordService chatRecordService;
    @Transactional(rollbackFor = Exception.class)
    public void saveCompletedTurn(String userName, String question, String answer) {
        //int seq = chatRecordService.nextMessageSeq(userName);
        chatRecordService.saveChatRecord(userName, question, ChatTypeEnum.USER);
        chatRecordService.saveChatRecord(userName, answer, ChatTypeEnum.BOT);
    }
}
