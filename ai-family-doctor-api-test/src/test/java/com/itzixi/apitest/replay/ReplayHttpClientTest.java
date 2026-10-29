package com.itzixi.apitest.replay;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class ReplayHttpClientTest {
    interface Handler {void accept(HttpExchange e) throws Exception;}
    static class Server implements AutoCloseable {
        final HttpServer server;final ExecutorService executor;
        Server(Handler handler) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            executor=Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;});
            server.setExecutor(executor);
            server.createContext("/chat/stream",e->{try{handler.accept(e);}catch(Exception ignored){}finally{e.close();}});server.start();
        }
        URI base(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        public void close(){server.stop(0);executor.shutdownNow();}
    }
    void headers(HttpExchange e) throws Exception {
        e.getResponseHeaders().set("Content-Type","text/event-stream");e.getResponseHeaders().set("X-Request-Id","target");e.sendResponseHeaders(200,0);
    }
    @Test void readsIncrementalChineseAndMatchesHeaderRequestId() throws Exception {
        try(var server=new Server(e->{headers(e);String text="event: delta\ndata: {\"requestId\":\"target\",\"text\":\"你好\\n世界\"}\n\nevent: done\ndata: {\"requestId\":\"target\"}\n\n";
            for(byte b:text.getBytes(StandardCharsets.UTF_8)){e.getResponseBody().write(b);e.getResponseBody().flush();}});
            var client=new ReplayHttpClient(Duration.ofSeconds(3))) {
            var result=client.stream(server.base(),new ObjectMapper().readTree("{\"message\":\"hi\"}"),"test-token");
            assertThat(result.answer()).isEqualTo("你好\n世界");assertThat(result.terminal()).isEqualTo("done");assertThat(result.requestId()).isEqualTo("target");
        }
    }
    @Test void prematureEofAndMismatchedIdsFail() throws Exception {
        for(String event:new String[]{"event: delta\ndata: {\"requestId\":\"target\",\"text\":\"partial\"}\n\n",
                "event: done\ndata: {\"requestId\":\"different\"}\n\n"}) {
            try(var server=new Server(e->{headers(e);e.getResponseBody().write(event.getBytes(StandardCharsets.UTF_8));});var client=new ReplayHttpClient(Duration.ofSeconds(3))) {
                assertThatThrownBy(()->client.stream(server.base(),new ObjectMapper().createObjectNode(),"token")).isInstanceOf(ReplayFailure.class);
            }
        }
    }
    @Test void stalledBodyHasWholeRoundDeadline() throws Exception {
        try(var server=new Server(e->{headers(e);e.getResponseBody().write(": connected\n\n".getBytes());e.getResponseBody().flush();Thread.sleep(1500);});
            var client=new ReplayHttpClient(Duration.ofMillis(300))) {
            assertThatThrownBy(()->client.stream(server.base(),new ObjectMapper().createObjectNode(),"token")).isInstanceOf(ReplayFailure.class).hasMessage("STREAM_TIMEOUT");
        }
    }
    @Test void oversizedCommentIsRejectedBeforeNewlineOrEof() throws Exception {
        try(var server=new Server(e->{headers(e);byte[] line=new byte[1048580];java.util.Arrays.fill(line,(byte)'x');line[0]=':';
                e.getResponseBody().write(line);e.getResponseBody().flush();Thread.sleep(1000);});
            var client=new ReplayHttpClient(Duration.ofSeconds(3))) {
            assertThatThrownBy(()->client.stream(server.base(),new ObjectMapper().createObjectNode(),"token"))
                .isInstanceOf(ReplayFailure.class).hasMessage("STREAM_LINE_TOO_LARGE");
        }
    }
    @Test void failedEventIsReturnedForMetricDiagnosis() throws Exception {
        try(var server=new Server(e->{headers(e);e.getResponseBody().write("event: failed\ndata: {\"requestId\":\"target\",\"code\":\"MODEL_ERROR\",\"message\":\"failed\"}\n\n".getBytes());});
            var client=new ReplayHttpClient(Duration.ofSeconds(3))) {
            var result=client.stream(server.base(),new ObjectMapper().createObjectNode(),"token");
            assertThat(result.terminal()).isEqualTo("failed");assertThat(result.errorCode()).isEqualTo("MODEL_ERROR");
        }
    }
}
