package com.itzixi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.itzixi.mapper")
public class Application {

    //    http://localhost:8080/hello/world
    //    http://127.0.0.1:8080/hello/world

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}