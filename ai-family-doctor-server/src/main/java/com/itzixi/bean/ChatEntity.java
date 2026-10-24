package com.itzixi.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data//Lombok生成set get方法
@ToString
@AllArgsConstructor//全参构造函数
@NoArgsConstructor//无参构造函数
public class ChatEntity {
    private String message;
}
