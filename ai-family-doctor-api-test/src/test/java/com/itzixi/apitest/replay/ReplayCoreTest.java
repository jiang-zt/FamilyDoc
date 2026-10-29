package com.itzixi.apitest.replay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ReplayCoreTest {
    @TempDir Path dir;
    final ObjectMapper mapper=new ObjectMapper();
    String sample(String message) throws Exception {
        return mapper.writeValueAsString(Map.of("schemaVersion",1,"sampleId","sample","requestId","source","recordedAt","2026-10-28T00:00:00Z","actorKey","actor",
            "method","POST","path","/chat/stream","requestBody",Map.of("message",message,"history",List.of())));
    }
    @Test void readerSelectsExactlyOneSampleAndPreservesBody() throws Exception {
        Path p=dir.resolve("input.jsonl");Files.writeString(p,sample("完整问题")+"\n"+sample("second").replace("\"sample\"","\"second\"")+"\n");
        var value=new ReplaySampleReader().read(p,"sample");
        assertThat(value.requestBody().path("message").asText()).isEqualTo("完整问题");
        assertThat(value.sourceRequestId()).isEqualTo("source");
    }
    @Test void malformedOrForeignEndpointSamplesNeverReplay() throws Exception {
        Path p=dir.resolve("bad.jsonl");Files.writeString(p,sample("hi").replace("/chat/stream","/auth/register")+"\n");
        assertThatThrownBy(()->new ReplaySampleReader().read(p,null)).isInstanceOf(ReplayFailure.class);
        Files.writeString(p,"{broken}\n");assertThatThrownBy(()->new ReplaySampleReader().read(p,null)).isInstanceOf(ReplayFailure.class);
        Files.writeString(p,sample("hi"));assertThatThrownBy(()->new ReplaySampleReader().read(p,null)).isInstanceOf(ReplayFailure.class);
    }
    @Test void verifierMatchesExactQuestionAndGenerationButDoesNotRequireQualityPass() throws Exception {
        var body=mapper.readTree(sample("question")).get("requestBody");
        var input=new ReplaySampleReader.Sample("sample","source","actor",body);
        var result=new ReplayHttpClient.StreamResult(200,"target","done",null,"answer",1,2);
        Map<String,Object> row=new HashMap<>(Map.of("requestId","target","userName","Rp123456","question","question","status","SUCCESS","accuracyScore",10,"outputChars",6));
        ReplayMetricVerifier.validate(row,input,result,"Rp123456");
        row.put("question","different");assertThatThrownBy(()->ReplayMetricVerifier.validate(row,input,result,"Rp123456")).isInstanceOf(ReplayFailure.class);
    }
}
