package com.itzixi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

//创建 Spring 应用上下文（ApplicationContext）
//启动内嵌 Web 服务器（因为类路径里有 spring-boot-starter-web）
//加载所有 Bean、执行自动配置
//启动完成后阻塞主线程，让服务常驻运行，等待 HTTP 请求

@SpringBootApplication
@MapperScan("com.itzixi.mapper")// ④ 指定 Mapper 接口包
public class Application {

    //    http://localhost:8080/
    //    http://127.0.0.1:8080/

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
//SpringApplication.run 返回后，main 方法结束，
// 但非守护线程（Tomcat 的 accept 线程等）还在跑，JVM 不会退出，服务持续运行，处理请求直到被 Ctrl+C / kill 停止