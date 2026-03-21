package com.itzixi.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @ClassName HelloController
 * @Author 风间影月
 * @Version 1.0
 * @Description HelloController
 **/

/**
 * RestContoller 注解
 * 表示使用Restful规范 对外暴露的接口
 *
 * @RequestMapping("hello")定义映射
 */
@RestController
@RequestMapping("hello")
public class HelloController {

    //返回路径
    @GetMapping("world")
    public Object helloWorld() {
        return "Hello 风间影月~~~";
    }
}
