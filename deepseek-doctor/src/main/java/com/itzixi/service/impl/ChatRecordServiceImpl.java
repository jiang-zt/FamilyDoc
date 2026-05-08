package com.itzixi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.itzixi.bean.ChatRecord;
import com.itzixi.mapper.ChatRecordMapper;
import com.itzixi.service.ChatRecordService;
import com.itzixi.utils.ChatTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @ClassName ChatRecordServiceImpl
 * @Author
 * @Version 1.0
 * @Description ChatRecordServiceImpl
 **/
@Service
public class ChatRecordServiceImpl implements ChatRecordService {

    @Resource
    private ChatRecordMapper chatRecordMapper;

    @Override
    public void saveChatRecord(String userName, String message, ChatTypeEnum chatType) {

        ChatRecord chatRecord = new ChatRecord();
        chatRecord.setFamilyMember(userName);
        chatRecord.setContent(message);
        chatRecord.setChatType(chatType.type);
        chatRecord.setChatTime(LocalDateTime.now());

        chatRecordMapper.insert(chatRecord);
    }

    @Override
    public List<ChatRecord> getChatRecordList(String who) {

        QueryWrapper<ChatRecord> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("family_member", who);

        return chatRecordMapper.selectList(queryWrapper);
    }

    @Override
    public int deleteChatRecords(String userName) {
        QueryWrapper<ChatRecord> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("family_member", userName);
        return chatRecordMapper.delete(queryWrapper);
    }

    @Override
    public java.util.List<String> listChatUsers() {
        QueryWrapper<ChatRecord> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("distinct family_member");
        java.util.List<ChatRecord> records = chatRecordMapper.selectList(queryWrapper);
        java.util.List<String> users = new java.util.ArrayList<>();
        for (ChatRecord record : records) {
            if (record.getFamilyMember() != null) {
                users.add(record.getFamilyMember());
            }
        }
        return users;
    }
}
