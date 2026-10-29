package com.itzixi.apitest.replay;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

public class ReplaySampleReader {
    private final ObjectMapper mapper=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Sample(String sampleId,String sourceRequestId,String actorKey,JsonNode requestBody){}
    public Sample read(Path input,String selectedId) {
        try(var reader=Files.newBufferedReader(input,StandardCharsets.UTF_8)) {
            while(true) {
                String line=line(reader);
                if(line==null) break;
                JsonNode n=mapper.readTree(line);
                if(n==null || n.path("schemaVersion").asInt()!=1 || !"POST".equals(n.path("method").asText())
                        || !"/chat/stream".equals(n.path("path").asText())) throw new ReplayFailure("INVALID_SAMPLE_SCHEMA");
                for(String field:new String[]{"sampleId","requestId","actorKey","recordedAt"})
                    if(!n.path(field).isTextual() || n.path(field).asText().isBlank()) throw new ReplayFailure("INVALID_SAMPLE_SCHEMA");
                Instant.parse(n.path("recordedAt").asText());
                var body=n.path("requestBody");
                if(!body.isObject() || !body.path("message").isTextual() || body.path("message").asText().isBlank()
                        || (body.has("history") && !body.path("history").isArray())) throw new ReplayFailure("INVALID_SAMPLE_BODY");
                if(selectedId==null || selectedId.equals(n.path("sampleId").asText())) {
                    return new Sample(n.path("sampleId").asText(),n.path("requestId").asText(),n.path("actorKey").asText(),body.deepCopy());
                }
            }
            throw new ReplayFailure("SAMPLE_NOT_FOUND");
        } catch(ReplayFailure ex){throw ex;} catch(Exception ex){throw new ReplayFailure("SAMPLE_READ_FAILED");}
    }
    private String line(BufferedReader reader) throws IOException {
        StringBuilder line=new StringBuilder();
        int c;
        while((c=reader.read())!=-1) {
            if(c=='\n') {
                if(!line.isEmpty() && line.charAt(line.length()-1)=='\r') line.setLength(line.length()-1);
                return line.toString();
            }
            if(line.length()>=1048576) throw new ReplayFailure("SAMPLE_LINE_TOO_LARGE");
            line.append((char)c);
        }
        if(!line.isEmpty()) throw new ReplayFailure("INCOMPLETE_SAMPLE_LINE");
        return null;
    }
}
