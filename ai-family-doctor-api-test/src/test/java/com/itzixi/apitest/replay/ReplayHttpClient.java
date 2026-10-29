package com.itzixi.apitest.replay;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class ReplayHttpClient implements AutoCloseable {
    private final ObjectMapper mapper=new ObjectMapper();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Duration timeout;
    public ReplayHttpClient(Duration timeout){this.timeout=timeout;}
    public JsonNode json(URI uri,Map<String,String> body) {
        try {
            var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
            if(body==null) builder.GET();
            else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),StandardCharsets.UTF_8));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200) throw new ReplayFailure("PREFLIGHT_OR_AUTH_HTTP_"+response.statusCode());
            return mapper.readTree(response.body());
        }catch(ReplayFailure ex){throw ex;}catch(Exception ex){throw new ReplayFailure("PREFLIGHT_OR_AUTH_FAILED");}
    }
    public String registerAndLogin(URI base,String username,String password) {
        json(base.resolve("/auth/register"),Map.of("username",username,"password",password));
        var response=json(base.resolve("/auth/login"),Map.of("username",username,"password",password));
        String token=response.path("token").asText();
        if(token.isBlank() || !username.equals(response.path("user").path("username").asText())) throw new ReplayFailure("TEST_LOGIN_INVALID");
        return token;
    }
    public record StreamResult(int httpStatus,String requestId,String terminal,String errorCode,String answer,long firstDeltaMs,long totalMs){}
    public StreamResult stream(URI base,JsonNode body,String token) {
        var stream=new AtomicReference<InputStream>();var requestId=new AtomicReference<String>();
        var executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"replay-sse-reader");t.setDaemon(true);return t;});
        Future<StreamResult> task=executor.submit(()->consume(base,body,token,stream,requestId));
        try {return task.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(TimeoutException ex){throw new ReplayFailure("STREAM_TIMEOUT",requestId.get());}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new ReplayFailure("REPLAY_INTERRUPTED",requestId.get());}
        catch(ExecutionException ex){
            String code=ex.getCause() instanceof ReplayFailure f?f.getMessage():
                ex.getCause() instanceof HttpTimeoutException?"STREAM_TIMEOUT":"STREAM_READ_FAILED";
            throw new ReplayFailure(code,requestId.get());
        } finally {
            task.cancel(true);InputStream active=stream.get();
            if(active!=null) try{active.close();}catch(IOException ignored){}
            executor.shutdownNow();
        }
    }
    private StreamResult consume(URI base,JsonNode body,String token,AtomicReference<InputStream> active,AtomicReference<String> id) throws Exception {
        long start=System.nanoTime();long first=-1;
        var request=HttpRequest.newBuilder(base.resolve("/chat/stream")).timeout(timeout)
            .header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Accept","text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),StandardCharsets.UTF_8)).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        active.set(response.body());
        response.headers().firstValue("X-Request-Id").filter(s->!s.isBlank()).ifPresent(id::set);
        try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
            if(response.statusCode()!=200) throw new ReplayFailure("STREAM_HTTP_"+response.statusCode());
            if(!response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) throw new ReplayFailure("NOT_EVENT_STREAM");
            String line,event="",terminal=null,error=null;StringBuilder data=new StringBuilder(),answer=new StringBuilder();
            while((line=boundedLine(reader))!=null) {
                if(line.isEmpty()) {
                    if(data.isEmpty()){event="";continue;}
                    if(Set.of("delta","done","failed").contains(event)) {
                        if(terminal!=null) throw new ReplayFailure("EVENT_AFTER_TERMINAL");
                        var payload=mapper.readTree(data.toString());String incoming=payload.path("requestId").asText();
                        if(incoming.isBlank() || (id.get()!=null && !id.get().equals(incoming))) throw new ReplayFailure("REQUEST_ID_MISMATCH");
                        id.set(incoming);
                        if("delta".equals(event)) {
                            if(!payload.path("text").isTextual() || payload.path("text").asText().isEmpty()) throw new ReplayFailure("INVALID_DELTA");
                            if(first<0) first=elapsed(start);
                            answer.append(payload.path("text").asText());
                            if(answer.length()>2000000) throw new ReplayFailure("ANSWER_TOO_LARGE");
                        } else {
                            terminal=event;
                            if("failed".equals(event)) {
                                error=payload.path("code").asText();
                                if(!error.matches("[A-Z0-9_]{1,64}")) throw new ReplayFailure("INVALID_FAILED_EVENT");
                            }
                        }
                    }
                    data.setLength(0);event="";
                } else if(!line.startsWith(":")) {
                    int colon=line.indexOf(':');String field=colon<0?line:line.substring(0,colon);
                    String value=colon<0?"":line.substring(colon+1);if(value.startsWith(" ")) value=value.substring(1);
                    if("event".equals(field)) event=value;
                    if("data".equals(field)) {if(!data.isEmpty())data.append('\n');data.append(value);if(data.length()>1048576)throw new ReplayFailure("EVENT_TOO_LARGE");}
                }
            }
            if(terminal==null) throw new ReplayFailure("UNCONFIRMED_EOF");
            return new StreamResult(200,id.get(),terminal,error,answer.toString(),first,elapsed(start));
        }
    }
    private String boundedLine(BufferedReader reader) throws IOException {
        StringBuilder line=new StringBuilder();int c;
        while((c=reader.read())!=-1) {
            if(c=='\n') return line.toString();
            if(c=='\r') {
                reader.mark(1);int next=reader.read();
                if(next!=-1 && next!='\n') reader.reset();
                return line.toString();
            }
            if(line.length()>=1048576) throw new ReplayFailure("STREAM_LINE_TOO_LARGE");
            line.append((char)c);
        }
        return line.isEmpty()?null:line.toString();
    }
    private long elapsed(long start){return Duration.ofNanos(System.nanoTime()-start).toMillis();}
    @Override public void close(){client.shutdownNow();}
}
