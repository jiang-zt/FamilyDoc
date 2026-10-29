package com.itzixi.apitest.replay;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class ReplaySafetyTest {
    @TempDir Path dir;
    @Test void foreignTargetFailsBeforeAnyRegistrationAndReportExcludesSecrets() throws Exception {
        var registrations=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/replay/environment",e->{
            byte[] body="{\"profile\":\"dev\",\"database\":\"ai_family_doctor_replay\",\"mock\":true,\"recording\":false}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });
        server.createContext("/auth/register",e->{registrations.incrementAndGet();e.sendResponseHeaders(500,-1);e.close();});server.start();
        try {
            var config=new SingleReplay.Config(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
                "jdbc:mysql://127.0.0.1:3306/ai_family_doctor_replay","unit-secret-never-print",
                Path.of("src/test/resources/replay/single.jsonl"),dir,null,Duration.ofSeconds(2));
            var result=new SingleReplay().execute(config);
            assertThat(result.passed()).isFalse();assertThat(registrations).hasValue(0);
            String text=Files.readString(result.report());
            assertThat(new ObjectMapper().readTree(text).path("failureCode").asText()).isEqualTo("TARGET_NOT_ISOLATED_REPLAY");
            assertThat(text).doesNotContain("unit-secret-never-print","请简短介绍你的用途","Authorization","token");
        } finally {server.stop(0);}
    }
    @Test void developmentDatabaseIsRejectedBeforeNetwork() throws Exception {
        var config=new SingleReplay.Config(URI.create("http://127.0.0.1:1"),"jdbc:mysql://127.0.0.1:3306/deepseek_doctor",
            "unit-secret-never-print",Path.of("src/test/resources/replay/single.jsonl"),dir,null,Duration.ofSeconds(2));
        var result=new SingleReplay().execute(config);
        assertThat(result.passed()).isFalse();
        assertThat(Files.readString(result.report())).contains("INVALID_REPLAY_CONFIG").doesNotContain("unit-secret-never-print");
    }
}
