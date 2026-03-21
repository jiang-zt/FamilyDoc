package com.itzixi.bean;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@ToString
@TableName("app_user")
public class AppUser {

    private String id;
    private String username;
    private String passwordHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
