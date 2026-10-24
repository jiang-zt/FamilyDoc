package com.itzixi.bean;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@ToString
@TableName("chat_record")
public class ChatRecord {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String content;
    private String chatType;
    private LocalDateTime chatTime;
    private String familyMember;
}
