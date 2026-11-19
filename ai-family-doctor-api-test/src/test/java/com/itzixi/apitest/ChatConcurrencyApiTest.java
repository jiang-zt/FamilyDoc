package com.itzixi.apitest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzixi.apitest.support.ApiTestBase;
import com.itzixi.apitest.support.AuthSupport;
import com.itzixi.apitest.support.TestConfig;

import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.restassured.RestAssured.given;

/*
“按用户登记生成占用”的 ChatGenerationGuard
| 编号 | 测试场景 | 校验目标 |
|---|---|---|
| **H01** | 同用户互斥:同一用户，第一个流式请求未完成时再发送请求 | 第二个返回 `409`，第一个正常完成 |
| **H02** | 不同用户隔离 :用户 A 生成期间，用户 B 发起请求 | 两个请求均被接受并正常完成 |
| **H03** | 正常释放: 同一用户，第一次生成结束后再次请求 | 第二次正常执行，说明占用已释放 |
| **H04** | 生成与清空互斥: 同一用户，生成期间请求清空记录，结束后再次清空 | 期间返回 `409`，结束后返回 `200` |
*/
//同一个用户两个流式请求重叠：一个进入生成；另一个 HTTP 409；worker 只启动一次
public class ChatConcurrencyApiTest extends ApiTestBase{
    @Test
    void sameUserSecondStreamIsRejectedWhileFirstIsGenerating() throws Exception {
        // 两个请求共用同一个用户和 token。
        var user = AuthSupport.registerRandomUser();
        var first = (HttpURLConnection) URI.create(TestConfig.baseUrl() + "/chat/stream").toURL().openConnection();
        first.setRequestMethod("POST");
        first.setDoOutput(true);//开启输出
        first.setConnectTimeout(5_000);
        first.setReadTimeout(5_000);
        first.setRequestProperty(
                "Authorization", "Bearer " + user.token()
        );
        first.setRequestProperty("Content-Type", "application/json");
        first.setRequestProperty("Accept", "text/event-stream");

        //序列化请求体
        byte[] body = new ObjectMapper().writeValueAsBytes(
                Map.of("message", "H01：第一个请求")//序列化为 JSON 格式的字节数组（byte[]）
        );
//测试流式响应（SSE，Server-Sent Events），且需要在长连接中手动控制“发送”和“读取”的时机
//脱离 RestAssured 的高层封装，用底层的 HttpURLConnection 手动建立连接，手动写 Body，手动用 BufferedReader 去读流
        try{
            // 2. 获取底层输出流并写入
            try(var output = first.getOutputStream()){//getOutputStream() 打开了通向服务器的管道（OutputStream），准备向服务器发送数据
                output.write(body);//把刚才序列化好的字节数组，通过管道怼进网络里，发送给服务器
            }//自动调用 output.close()
            assertThat(first.getResponseCode()).isEqualTo(200);

            try(var reader = new BufferedReader(
                new InputStreamReader(first.getInputStream(),StandardCharsets.UTF_8)
            )){
                // 等待一个完整的 delta 事件，确认第一个请求已经输出。
                // 1. 等待第一个请求（SSE流）输出第一个 delta 事件，确保服务端正在生成中
                assertTrue(awaitEvent(reader, "delta"), "第一个请求应已开始返回 delta 事件");
                // 2. 准备 RestAssured 配置：设置连接和读取超时为 5 秒
                HttpClientConfig httpClientConfig = HttpClientConfig.httpClientConfig().setParam("http.connection.timeout", 5000).setParam("http.socket.timeout", 5000);
                RestAssuredConfig concurrentTestConfig = RestAssuredConfig.config().httpClient(httpClientConfig);
                // 3. 模拟同一用户发起第二个并发请求
                Response second = given().config(concurrentTestConfig)
                .header("Authorization", "Bearer " + user.token())
                .contentType(ContentType.JSON)
                .body(Map.of("message", "H01：第二个请求"))
                .when()
                .post("/chat/stream");

                // 4. 断言：并发限制生效，第二个请求被拒绝
                assertThat(second.statusCode())
                .as("并发限制状态码：第一个任务生成期间，同用户的新请求应被拒绝")
                .isEqualTo(409);
                // 第二个请求被拒绝，不应影响第一个请求正常完成。
                // 5. 断言：第一个请求未受干扰，正常走到结束（done）
                assertTrue(awaitEvent(reader, "done"), "第二个请求被拒绝后，第一个请求应能正常完成");
            
            }
        }finally{
            first.disconnect();
        }
        
    }

    ////用户 A 正在生成时，用户 B 也能开始生成，不能误把不同用户一起限制
    @Test
    void differentUsersCanStreamWhileFirstIsStillGenerating() throws Exception {

        // 1. 两个不同账号，分别使用自己的 token。
        var userA = AuthSupport.registerRandomUser();
        var userB = AuthSupport.registerRandomUser();

        var connectionA = openStream(
                userA.token(), "H02：用户A的请求"
        );

        HttpURLConnection connectionB = null;

        try {
            assertThat(connectionA.getResponseCode())
                    .as("用户A的请求应被接受")
                    .isEqualTo(200);

            try (var readerA = new BufferedReader(
                    new InputStreamReader(
                            connectionA.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            )) {
                // 2. 确认 A 已开始输出，此时不关闭 A 的连接。
                assertThat(awaitEvent(readerA, "delta"))
                        .as("用户A应开始生成")
                        .isTrue();

                // 3. A 仍在生成时，用户 B 发起请求。
                connectionB = openStream(
                        userB.token(), "H02：用户B的请求"
                );

                assertThat(connectionB.getResponseCode())
                        .as("用户B不能因为用户A正在生成而被拒绝")
                        .isEqualTo(200);

                try (var readerB = new BufferedReader(
                        new InputStreamReader(
                                connectionB.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
                    assertThat(awaitEvent(readerB, "delta"))
                            .as("用户B也应开始生成")
                            .isTrue();

                    // 4. 两个请求最终都正常完成。
                    assertThat(awaitEvent(readerA, "done"))
                            .as("用户A应正常完成")
                            .isTrue();

                    assertThat(awaitEvent(readerB, "done"))
                            .as("用户B应正常完成")
                            .isTrue();
                }
            }
        } finally {
            connectionA.disconnect();

            if (connectionB != null) {
                connectionB.disconnect();
            }
        }
    }
    //第一个请求完成并释放占用后，同一用户能够再次发起请求
    @Test
    void sameUserCanStartAgainAfterFirstStreamCompletes() throws Exception {
        // 两次请求使用同一个账号。
        var user = AuthSupport.registerRandomUser();
        // 1. 第一个请求正常生成。
        var first = openStream(user.token(), "H03：第一次请求");
        try {
            assertThat(first.getResponseCode())
                    .as("第一次请求应被接受")
                    .isEqualTo(200);
            try (var reader = new BufferedReader(new InputStreamReader(
                                first.getInputStream(),
                                StandardCharsets.UTF_8)
                )){
                    assertThat(awaitEvent(reader, "delta"))
                            .as("第一次请求应开始输出")
                            .isTrue();
        
                    assertThat(awaitEvent(reader, "done"))
                            .as("第一次请求应生成完成")
                            .isTrue();
        
                    // 等到 EOF，确认整个响应流结束。
                    // 若迟迟不结束，已有的读取超时会使测试报错。
                    while (reader.readLine() != null) {
                        // 读取剩余内容，直到服务端关闭响应。
                    }
                }
            } finally {
                first.disconnect();
            }
            // 2. 第一个响应已结束，同一用户再次请求。
            var second = openStream(user.token(), "H03：第二次请求");
            try {
                assertThat(second.getResponseCode())
                        .as("第一次完成后，占用应释放，第二次不能返回409")
                        .isEqualTo(200);
                try (var reader = new BufferedReader(
                            new InputStreamReader(
                                    second.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    )) {
                        assertThat(awaitEvent(reader, "delta"))
                                .as("第二次请求应开始输出")
                                .isTrue();
            
                        assertThat(awaitEvent(reader, "done"))
                                .as("第二次请求应正常完成")
                                .isTrue();
            
                        while (reader.readLine() != null) {
                            // 等第二个响应也完整结束。
                        }
                    }
                } finally {
                    second.disconnect();
                }
                    
        

    }
    //生成期间，同一用户清空记录返回 409
    @Test
    void sameUserCannotClearRecordsWhileGenerating() throws Exception {
        var user = AuthSupport.registerRandomUser();
        var stream = openStream(user.token(), "H04：生成期间尝试清空");//http连接/stram

        try {
            assertThat(stream.getResponseCode()).isEqualTo(200);
            try (var reader = new BufferedReader(
                new InputStreamReader(
                        stream.getInputStream(),
                        StandardCharsets.UTF_8
                )
        )) {
            // 1. 确认生成已经开始。
            assertThat(awaitEvent(reader, "delta"))
                    .as("流式请求应开始生成")
                    .isTrue();

            // 2. 使用同一用户，在生成期间请求清空。
            given().header(
                            "Authorization",
                            "Bearer " + user.token()
                    )
                    .when()
                    .delete("/chat/records")
                    .then()
                    .statusCode(409);

            // 3. 清空被拒绝，原生成任务仍应正常完成。
            assertThat(awaitEvent(reader, "done"))
                    .as("被拒绝的清空操作不应影响生成")
                    .isTrue();

            while (reader.readLine() != null) {
                        // 等待响应完整结束。
            }
        }
    }finally{
        stream.disconnect();
    }
    // 4. 生成结束后，再次清空应成功。
    given()
            .header("Authorization", "Bearer " + user.token())
            .when()
            .delete("/chat/records")
            .then()
            .statusCode(200);


    }

    private boolean awaitEvent(BufferedReader reader,String expected) throws Exception{
        long deadline = System.nanoTime()+java.time.Duration.ofSeconds(45).toNanos();//等待45s
        String event = null;
        String line;
//readLine() 会阻塞，直到读取到一行文本。如果流结束了（服务端关闭了连接），它会返回 null
/* 
event: delta
data: {"content":"你"}

event: done
data: {"content":""}

*/
        while(System.nanoTime()<deadline && (line=reader.readLine())!=null){
            //读到了字符串，赋值给 line，进入循环体
            if(line.startsWith("event:")){
                event = line.substring(6).trim();
            }
            // 空行表示一个 SSE 事件读取完毕。
            if(line.isEmpty()){
                assertThat(event).as("第一个请求不能产生 failed 事件")//给这个断言起个名字/描述
                .isNotEqualTo("failed");

                if (expected.equals(event)) {
                    return true;
                }
                if ("done".equals(event)) {
                    return false;
                }
                event = null;
            }
        }
        return false;

    }

    
    private HttpURLConnection openStream(String token, String message) throws Exception{
        var connection = (HttpURLConnection) URI.create(TestConfig.baseUrl() + "/chat/stream").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        connection.setRequestProperty(
                "Authorization", "Bearer " + token
        );
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        try {
            byte[] body = new ObjectMapper().writeValueAsBytes(
                    Map.of("message", message)
            );
    
            try (var output = connection.getOutputStream()) {
                output.write(body);
            }
    
            return connection;
        } catch (Exception ex) {
            connection.disconnect();
            throw ex;
        }

    }
}
