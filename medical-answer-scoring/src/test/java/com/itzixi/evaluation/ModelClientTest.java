package com.itzixi.evaluation;

import com.itzixi.evaluation.client.ModelClient;
import com.itzixi.evaluation.model.EvaluationModels.Message;
import com.itzixi.evaluation.support.JsonSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ModelClientTest {
    @TempDir Path directory;

    @Test
    void cliRunsTheWholeSuiteAgainstLocalHttpWithoutAnyExternalModel() throws Exception {
        var generated=new AtomicInteger();
        var judged=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/completion",exchange -> {
            var body=JsonSupport.object(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            boolean generation=body.get("model").equals("generator-fixture");
            if (generation) generated.incrementAndGet(); else judged.incrementAndGet();
            String content=generation?EvaluationPipelineTest.HTML:EvaluationPipelineTest.judgeJson(
                    new com.itzixi.evaluation.runner.ResponseExecutor().extract(EvaluationPipelineTest.HTML));
            byte[] bytes=JsonSupport.stringify(Map.of("id","local-request","model",body.get("model"),"choices",
                    List.of(Map.of("finish_reason","stop","message",Map.of("content",content))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/completion";
            var environment=Map.of("EVAL_GENERATOR_ENDPOINT",endpoint,"EVAL_GENERATOR_MODEL","generator-fixture",
                    "EVAL_JUDGE_ENDPOINT",endpoint,"EVAL_JUDGE_MODEL","judge-fixture");
            Path suite=EvaluationConfigFixture.create(directory.resolve("configuration"));
            Path output=directory.resolve("run");
            var stdout=new ByteArrayOutputStream();var stderr=new ByteArrayOutputStream();
            int code=new EvaluationCli(environment).run(new String[]{"--suite",suite.toString(),"--output-dir",output.toString()},
                    new PrintStream(stdout,true,StandardCharsets.UTF_8),new PrintStream(stderr,true,StandardCharsets.UTF_8));
            assertThat(code).as(stderr.toString(StandardCharsets.UTF_8)).isZero();
            assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("suite_status=PASS", "generation_calls=2 judge_calls=2");
            assertThat(generated.get()).isEqualTo(2);assertThat(judged.get()).isEqualTo(2);
            assertThat(Files.readString(output.resolve("report.html"))).contains("0001", "0002");
        } finally { server.stop(0); }
    }
    @Test
    void sendsOneNonStreamingCompletionAndReadsUsageWithoutExposingCredentials() throws Exception {
        var body=new AtomicReference<String>();
        var auth=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response="{\"id\":\"req-1\",\"model\":\"model-a\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"你好\"}}],\"usage\":{\"total_tokens\":7}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var settings=new ModelClient.Settings(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions"),"model-a","secret-key",0.45,1024);
            var result=ModelClient.http(settings).complete(List.of(new Message("user","test")),Duration.ofSeconds(3));
            assertThat(result.content()).isEqualTo("你好");
            assertThat(result.requestId()).isEqualTo("req-1");
            assertThat(result.usage()).containsKey("total_tokens");
            assertThat(auth.get()).isEqualTo("Bearer secret-key");
            assertThat(JsonSupport.object(body.get())).containsEntry("stream",false);
            assertThat(body.get()).doesNotContain("secret-key");
            assertThat(settings.toString()).doesNotContain("secret-key");
        } finally { server.stop(0); }
    }

    @Test
    void rejectedRequestsCanRetryButTruncatedResponsesCannotPass() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/reject",exchange -> { exchange.sendResponseHeaders(429,-1);exchange.close(); });
        server.createContext("/truncated",exchange -> {
            byte[] response="{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}".getBytes();
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var rejected=ModelClient.http(new ModelClient.Settings(URI.create(base+"/reject"),"m","",0,10));
            assertThatThrownBy(() -> rejected.complete(List.of(new Message("user","q")),Duration.ofSeconds(2)))
                    .isInstanceOfSatisfying(ModelClient.ModelException.class,e -> {
                        assertThat(e.retryable()).isTrue();assertThat(e.safeToRetry()).isTrue();
                    });
            var truncated=ModelClient.http(new ModelClient.Settings(URI.create(base+"/truncated"),"m","",0,10));
            assertThatThrownBy(() -> truncated.complete(List.of(new Message("user","q")),Duration.ofSeconds(2)))
                    .hasMessageContaining("INCOMPLETE_RESPONSE");
        } finally { server.stop(0); }
    }
}
