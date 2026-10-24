package com.itzixi.service;

import com.itzixi.bean.ChatRecord;
import com.itzixi.utils.ChatTypeEnum;

import java.util.List;

public interface ChatRecordService {

    /**
     * @Description: 保存用户和AI的聊天记录
     * @Author 
     * @param userName
     * @param message
     * @param chatType
     */
    public void saveChatRecord(String userName, String message, ChatTypeEnum chatType);

    /**
     * @Description: 查询用户和AI的历史聊天记录
     * @Author 
     * @param userName
     * @return List<ChatRecord>
     */
    public List<ChatRecord> getChatRecordList(String userName);

    /**
     * 删除用户的聊天记录
     * @param userName
     * @return 删除条数
     */
    public int deleteChatRecords(String userName);

    /**
     * 查询历史聊天中的用户列表（distinct family_member）
     * @return 用户名列表
     */
    public java.util.List<String> listChatUsers();

    List<ChatRecord> getRecentMessages(String userName, int limit); // 倒序取出，内存 reverse 恢复正序
    int nextMessageSeq(String userName);                             // MAX+1（供保存用）


}
