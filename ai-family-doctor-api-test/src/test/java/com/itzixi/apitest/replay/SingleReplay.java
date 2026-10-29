package com.itzixi.apitest.replay;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class SingleReplay {
    public record Config(URI baseUrl,String jdbcUrl,String dbPassword,Path input,Path output,String sampleId,Duration timeout) {
        @Override public String toString(){return "SingleReplay.Config[credentials hidden]";}
    }
    public record Result(boolean passed,Path report){}
    public Result execute(Config config) throws Exception {
        String runId=UUID.randomUUID().toString();
        Map<String,Object> report=new LinkedHashMap<>();report.put("runId",runId);report.put("startedAt",Instant.now().toString());
        Path reportPath=config.output().resolve(runId+".json");boolean passed=false;
        try {
            validateConfig(config);
            var sample=new ReplaySampleReader().read(config.input(),config.sampleId());
            report.put("sampleId",sample.sampleId());report.put("sourceRequestId",sample.sourceRequestId());report.put("actorKey",sample.actorKey());
            report.put("baseUrl",config.baseUrl().toString());report.put("targetDatabase","ai_family_doctor_replay");
            try(var client=new ReplayHttpClient(config.timeout())) {
                var environment=client.json(config.baseUrl().resolve("/internal/replay/environment"),null);
                if(!"replay".equals(environment.path("profile").asText()) || !"ai_family_doctor_replay".equals(environment.path("database").asText())
                    || !environment.path("mock").asBoolean() || !environment.has("recording") || environment.path("recording").asBoolean()) throw new ReplayFailure("TARGET_NOT_ISOLATED_REPLAY");
                var verifier=new ReplayMetricVerifier(config.jdbcUrl(),config.dbPassword());verifier.preflight();
                String username="Rp"+UUID.randomUUID().toString().replace("-","").substring(0,6);
                report.put("testUser",username);
                String token=client.registerAndLogin(config.baseUrl(),username,UUID.randomUUID().toString());
                var stream=client.stream(config.baseUrl(),sample.requestBody(),token);
                report.put("targetRequestId",stream.requestId());report.put("httpStatus",stream.httpStatus());report.put("terminal",stream.terminal());
                report.put("errorCode",stream.errorCode());report.put("firstDeltaMs",stream.firstDeltaMs());report.put("totalMs",stream.totalMs());
                var metric=verifier.find(stream.requestId(),Duration.ofSeconds(5));
                var safeMetric=new LinkedHashMap<>(metric);safeMetric.remove("question");report.put("metric",safeMetric);
                if(!"done".equals(stream.terminal()))throw new ReplayFailure("GENERATION_FAILED");
                ReplayMetricVerifier.validate(metric,sample,stream,username);
                passed=true;
            }
        } catch(ReplayFailure ex) {
            report.put("failureCode",ex.getMessage());if(ex.requestId()!=null)report.put("targetRequestId",ex.requestId());
        } catch(Exception ex) {report.put("failureCode","UNEXPECTED_REPLAY_FAILURE");}
        report.put("status",passed?"PASS":"FAIL");report.put("finishedAt",Instant.now().toString());
        Files.createDirectories(config.output());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(),report);
        return new Result(passed,reportPath);
    }
    private void validateConfig(Config c) {
        URI base=c.baseUrl();
        URI jdbc=URI.create(c.jdbcUrl().replaceFirst("^jdbc:",""));
        if(!"http".equals(base.getScheme()) || !Set.of("127.0.0.1","localhost").contains(base.getHost()) || base.getUserInfo()!=null
            || (base.getPath()!=null && !base.getPath().isEmpty() && !"/".equals(base.getPath())) || base.getQuery()!=null
            || !Set.of("127.0.0.1","localhost").contains(jdbc.getHost()) || !"/ai_family_doctor_replay".equals(jdbc.getPath())
            || c.dbPassword()==null || c.dbPassword().isBlank() || c.timeout().isZero() || c.timeout().isNegative()) throw new ReplayFailure("INVALID_REPLAY_CONFIG");
    }
}
