package com.itzixi.apitest.replay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

/** 不继承 ApiTestBase，不触发默认开发库清理；仅显式启用时执行一条样本。 */
@EnabledIfSystemProperty(named="replay.enabled",matches="true")
class ReplayRunTest {
    @Test void replayOneSample() throws Exception {
        String host=System.getenv().getOrDefault("REPLAY_DB_HOST","127.0.0.1");
        String port=System.getenv().getOrDefault("REPLAY_DB_PORT","3306");
        String jdbc="jdbc:mysql://"+host+":"+port+"/ai_family_doctor_replay?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=5000";
        String input=System.getProperty("replay.input");assertThat(input).as("需要 -Dreplay.input 指定已完成的 JSONL 文件").isNotBlank();
        var config=new SingleReplay.Config(URI.create(System.getProperty("replay.baseUrl","http://127.0.0.1:18080")),jdbc,
            System.getenv("REPLAY_DB_PASSWORD"),Path.of(input),Path.of(System.getProperty("replay.output","target/replay")),
            System.getProperty("replay.sampleId"),Duration.ofSeconds(Long.parseLong(System.getProperty("replay.timeoutSeconds","190"))));
        var result=new SingleReplay().execute(config);
        System.out.println("单条回放报告："+result.report().toAbsolutePath());
        assertThat(result.passed()).as("回放失败，请查看报告（不自动重试、不自动删除测试数据）").isTrue();
    }
}
