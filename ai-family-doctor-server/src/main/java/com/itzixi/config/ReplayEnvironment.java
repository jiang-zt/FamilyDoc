package com.itzixi.config;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.net.URI;
import java.util.*;

/** 第一阶段仅允许本机、专用库/账号和 Mock，配置错误时启动失败。 */
@RestController
@Profile("replay")
public class ReplayEnvironment {
    private final Environment env;
    private final DataSource dataSource;
    public ReplayEnvironment(Environment env,DataSource dataSource){this.env=env;this.dataSource=dataSource;}
    @PostConstruct public void verify() throws Exception {
        if(!Arrays.equals(env.getActiveProfiles(),new String[]{"replay"})) fail();
        if(!"127.0.0.1".equals(env.getProperty("server.address"))
            || !"family_replay".equals(env.getProperty("spring.datasource.username"))
            || !env.getProperty("dashscope.mock.enabled",Boolean.class,false)
            || env.getProperty("chat.traffic-recording.enabled",Boolean.class,false)) fail();
        URI uri=URI.create(Objects.requireNonNull(env.getProperty("spring.datasource.url")).replaceFirst("^jdbc:",""));
        if(!Set.of("localhost","127.0.0.1").contains(uri.getHost()) || !"/ai_family_doctor_replay".equals(uri.getPath())) fail();
        try(var connection=dataSource.getConnection()) {
            if(!"ai_family_doctor_replay".equals(connection.getCatalog())
                || !connection.getMetaData().getUserName().startsWith("family_replay@")) fail();
        }
    }
    private void fail(){throw new IllegalStateException("回放环境必须独立 profile、127.0.0.1、专用回放库/账号、Mock 且关闭录制");}
    @GetMapping("/internal/replay/environment")
    public Map<String,Object> environment(){return Map.of("profile","replay","database","ai_family_doctor_replay","mock",true,"recording",false);}
}
