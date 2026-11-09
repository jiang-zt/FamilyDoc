package com.itzixi.evaluation;

import com.itzixi.evaluation.cases.HaiCoachingCaseLoader;
import com.itzixi.evaluation.client.ModelClient;
import com.itzixi.evaluation.config.EvaluationConfigLoader;
import com.itzixi.evaluation.judge.ResponseJudge;
import com.itzixi.evaluation.model.EvaluationModels.*;
import com.itzixi.evaluation.prompt.PromptBuilder;
import com.itzixi.evaluation.runner.ResponseExecutor;
import com.itzixi.evaluation.runner.SuiteRunner;
import com.itzixi.evaluation.support.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class EvaluationPipelineTest {
    @TempDir Path directory;
    static final String HTML = """
            <div class="section" data-slot="reflection"><strong>反映与澄清</strong><p>It is <strong>hard</strong> &amp; tiring.</p></div>
            <div data-slot='comfort' class='section'><strong>正常化与宽慰</strong><p>You are not alone.</p></div>
            <div data-slot="reframing"><strong>积极重构</strong><p>Small steps count.</p></div>
            <div data-slot="suggestion"><strong>建议</strong><ul><li>Prepare one meal.</li><li>Keep it simple.</li></ul></div>
            <p class="note">A note outside the four sections.</p>
            """;

    @Test
    void selectsCasesInSuiteOrderAndFiltersReferencesWithoutLeakingThemIntoGeneration() throws Exception {
        var entry = loaded().entries().getFirst();
        var cases = new HaiCoachingCaseLoader().load(entry);
        assertThat(cases).extracting(HaiCase::caseId).containsExactly("0002", "0001");
        assertThat(cases.getFirst().references().get("reflection")).hasSize(1);
        var prompt = new PromptBuilder().build(entry, cases.getFirst());
        assertThat(prompt.messages()).hasSize(2);
        assertThat(prompt.messages().getFirst().role()).isEqualTo("system");
        assertThat(prompt.messages().getLast().content()).isEqualTo("I struggle with cooking.");
        assertThat(prompt.messages().toString()).doesNotContain("REFERENCE_NOT_FOR_GENERATION", "UNSAFE_REFERENCE");
        assertThat(prompt.temperature()).isEqualTo(0.45);
    }

    @Test
    void missingIdsDuplicateSourceIdsAndMisalignedAnnotationsFailBeforeAnyModelCall() throws Exception {
        var entry = loaded().entries().getFirst();
        String original = Files.readString(entry.casesPath());
        Files.writeString(entry.casesPath(), original.replace("\"0002\"", "\"0003\""));
        assertThatThrownBy(() -> new HaiCoachingCaseLoader().load(entry)).hasMessageContaining("0002");
        Files.writeString(entry.casesPath(), original + original.lines().findFirst().orElseThrow() + "\n");
        assertThatThrownBy(() -> new HaiCoachingCaseLoader().load(entry)).hasMessageContaining("duplicate");
        Files.writeString(entry.casesPath(), original.replace("[\"Y\",\"N\"]", "[\"Y\"]"));
        assertThatThrownBy(() -> new HaiCoachingCaseLoader().load(entry)).hasMessageContaining("annotation");
    }

    @Test
    void extractsSlotTextPreservingEmphasisEntitiesAndListItemsButNotHeadingsOrNote() {
        var sections = new ResponseExecutor().extract(HTML);
        assertThat(sections.get("reflection")).isEqualTo("It is hard & tiring.");
        assertThat(sections.get("suggestion")).contains("Prepare one meal.", "Keep it simple.");
        assertThat(sections.values().toString()).doesNotContain("反映与澄清", "outside the four");
    }

    @Test
    void aHeadingAndHtmlWhitespaceAloneDoNotCountAsAResponse() {
        for (String whitespace:List.of("&nbsp;", "&#x2003;", "&#x202f;")) {
            String answer=HTML.replace("<p>It is <strong>hard</strong> &amp; tiring.</p>",whitespace);
            assertThatThrownBy(() -> new ResponseExecutor().extract(answer))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Empty section");
        }
    }

    @Test
    void missingDuplicateReorderedAndEmptySlotsAreNotSilentlyFilled() {
        for (String invalid : List.of(
                HTML.replace("data-slot=\"suggestion\"", "data-slot=\"unknown\""),
                HTML + "<div data-slot='reflection'><p>duplicate</p></div>",
                HTML.replace("data-slot=\"reflection\"", "data-slot=\"reframing\""),
                HTML.replace("<p>You are not alone.</p>", ""),
                "```html\n" + HTML + "\n```",
                "<html><body>" + HTML + "</body></html>")) {
            assertThatThrownBy(() -> new ResponseExecutor().extract(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void twoCasesUseExactlyTwoGenerationAndTwoJudgeCallsAndProduceReports() throws Exception {
        var prepared = SuiteRunner.prepare(loaded());
        AtomicInteger generations = new AtomicInteger();
        AtomicInteger judges = new AtomicInteger();
        ModelClient generator = (messages, timeout) -> {
            generations.incrementAndGet();
            assertThat(messages.toString()).doesNotContain("REFERENCE_NOT_FOR_GENERATION");
            return completion(HTML);
        };
        ModelClient judge = (messages, timeout) -> {
            judges.incrementAndGet();
            assertThat(messages.toString()).contains("REFERENCE_NOT_FOR_GENERATION").doesNotContain("UNSAFE_REFERENCE");
            return completion(judgeJson(new ResponseExecutor().extract(HTML)));
        };
        var result = new SuiteRunner(generator, judge).run(prepared, directory.resolve("output"));
        assertThat(result.status()).isEqualTo(Status.PASS);
        assertThat(result.generationCalls()).isEqualTo(2);
        assertThat(result.judgeCalls()).isEqualTo(2);
        assertThat(generations.get()).isEqualTo(2);
        assertThat(judges.get()).isEqualTo(2);
        assertThat(Files.readString(result.runDirectory().resolve("summary.json"))).contains("PASS");
        assertThat(Files.readString(result.runDirectory().resolve("report.html")))
                .contains("0001", "0002", "id=\"search\"", "逐项检查结果")
                .doesNotContain("评分与通过度口径", "程序组织的用例", "实际提交的问题", "运行快照指纹");
        try (var files=Files.list(result.runDirectory())) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("report.html","summary.json","results.json","manifest.json");
        }
        assertThat(Files.readString(result.runDirectory().resolve("summary.json"))).contains("\n  ");
    }

    @Test
    void invalidGenerationSkipsJudgeAndLeavesLaterCaseNotRun() throws Exception {
        AtomicInteger judgeCalls = new AtomicInteger();
        var result = new SuiteRunner((m,t) -> completion("<p>No sections</p>"), (m,t) -> {
            judgeCalls.incrementAndGet(); return completion("{}");
        }).run(SuiteRunner.prepare(loaded()), directory.resolve("output"));
        assertThat(result.status()).isEqualTo(Status.FAIL);
        assertThat(result.cases()).extracting(CaseResult::status).containsExactly(Status.FAIL, Status.NOT_RUN);
        assertThat(judgeCalls.get()).isZero();
        assertThat(Files.readString(result.runDirectory().resolve("results.json"))).contains("No sections");
    }

    @Test
    void judgeRetryReusesGenerationAndInvalidEvidenceIsAnEvaluationError() throws Exception {
        var prepared = SuiteRunner.prepare(loaded());
        AtomicInteger calls = new AtomicInteger();
        ModelClient judge = (m,t) -> {
            if (calls.getAndIncrement() == 0) throw new ModelClient.ModelException("HTTP_503", true, false);
            return completion(judgeJson(new ResponseExecutor().extract(HTML)));
        };
        var result = new SuiteRunner((m,t) -> completion(HTML), judge).run(prepared, directory.resolve("retry"));
        assertThat(result.status()).isEqualTo(Status.PASS);
        assertThat(result.generationCalls()).isEqualTo(2);
        assertThat(result.judgeCalls()).isEqualTo(3);
        var bad = new SuiteRunner((m,t) -> completion(HTML), (m,t) -> completion(
                judgeJson(new ResponseExecutor().extract(HTML)).replace("It is hard & tiring.", "invented evidence")))
                .run(prepared, directory.resolve("bad"));
        assertThat(bad.cases().getFirst().status()).isEqualTo(Status.EVALUATION_ERROR);
    }

    @Test
    void ambiguousGenerationTimeoutIsNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var result = new SuiteRunner((m,t) -> {
            calls.incrementAndGet(); throw new ModelClient.ModelException("REQUEST_TIMEOUT", true, false);
        }, (m,t) -> completion("{}")).run(SuiteRunner.prepare(loaded()), directory.resolve("timeout"));
        assertThat(result.status()).isEqualTo(Status.FAIL);
        assertThat(result.cases().getFirst().status()).isEqualTo(Status.ERROR);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void expiredSuiteStartsNoModelRequestsAndStillWritesSummary() throws Exception {
        var loaded = loaded();
        var prepared = SuiteRunner.prepare(loaded, System.nanoTime() - Duration.ofSeconds(181).toNanos());
        var result = new SuiteRunner((m,t) -> { throw new AssertionError("unexpected model request"); },
                (m,t) -> { throw new AssertionError("unexpected judge request"); })
                .run(prepared, directory.resolve("expired"));
        assertThat(result.status()).isEqualTo(Status.FAIL);
        assertThat(result.cases()).extracting(CaseResult::status).containsOnly(Status.NOT_RUN);
        assertThat(Files.readString(result.runDirectory().resolve("summary.json"))).contains("SUITE_TIMEOUT");
    }

    @Test
    void strictJsonRejectsDuplicateKeysTrailingTextAndYaml() {
        for (String invalid : List.of("{\"a\":1,\"a\":2}", "{a: yes}", "{} trailing", "{\"n\":NaN}")) {
            assertThatThrownBy(() -> JsonSupport.object(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(JsonSupport.object(JsonSupport.stringify(Map.of("text", "\"你好\"\n")))).containsEntry("text", "\"你好\"\n");
    }

    @Test
    void judgeReceivesItsOutputSchemaAndNullPassedRemainsAnEvaluationError() throws Exception {
        var prepared=SuiteRunner.prepare(loaded());
        var first=prepared.cases().getFirst();
        var sections=new ResponseExecutor().extract(HTML);
        var messages=new ResponseJudge().messages(first.testCase(),sections,HTML,first.entry().rubric(),first.checks());
        assertThat(messages.getLast().content()).contains("output_schema", "responses");
        var result=new SuiteRunner((m,t) -> completion(HTML), (m,t) -> completion(
                judgeJson(sections).replace("\"passed\":true", "\"passed\":null")))
                .run(prepared,directory.resolve("invalid-indices"));
        assertThat(result.cases().getFirst().status()).isEqualTo(Status.EVALUATION_ERROR);
        assertThat(Files.exists(result.runDirectory().resolve("report.html"))).isTrue();
    }

    @Test
    void lateResponseIsPreservedOnceAsAnErrorRatherThanBothSuccessAndFailure() throws Exception {
        var configuration=loaded();
        Files.writeString(configuration.suitePath(),Files.readString(configuration.suitePath())
                .replace("\"suite_timeout_seconds\": 180","\"suite_timeout_seconds\": 1"));
        var prepared=SuiteRunner.prepare(new EvaluationConfigLoader().load(configuration.suitePath()));
        var result=new SuiteRunner((m,t) -> {
            try { Thread.sleep(1100); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            return completion(HTML);
        }, (m,t) -> { throw new AssertionError("Judge must not be called after deadline"); })
                .run(prepared,directory.resolve("late"));
        var bundle=JsonSupport.object(Files.readString(result.runDirectory().resolve("results.json")));
        var first=JsonSupport.asObject(JsonSupport.list(bundle.get("cases")).getFirst());
        var attempts=JsonSupport.list(first.get("generation_attempts"));
        assertThat(attempts).hasSize(1);
        assertThat(JsonSupport.asObject(attempts.getFirst())).containsEntry("status","ERROR").containsEntry("content",HTML);
    }

    @Test
    void aReportWriteFailureCannotLeaveAFinalPassSummary() throws Exception {
        Path output=directory.resolve("unwritable-report");
        var prepared=SuiteRunner.prepare(loaded());
        assertThatThrownBy(() -> new SuiteRunner((m,t) -> completion(HTML), (m,t) -> {
            try { Files.createDirectories(output.resolve("report.html")); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            return completion(judgeJson(new ResponseExecutor().extract(HTML)));
        }).run(prepared,output)).isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(JsonSupport.object(Files.readString(output.resolve("summary.json"))))
                .containsEntry("status","FAIL").containsEntry("error","REPORT_WRITE_FAILED");
        assertThat(Files.isRegularFile(output.resolve("results.jsonl"))).isTrue();
        assertThat(Files.isRegularFile(output.resolve("snapshots/cases.jsonl"))).isTrue();
        assertThat(Files.exists(output.resolve("snapshots/render_report.py"))).isFalse();
    }

    @Test
    void changedPromptAfterPreflightIsRejectedBeforeCallingModels() throws Exception {
        var prepared=SuiteRunner.prepare(loaded());
        Path source=prepared.cases().getFirst().prompt().sourcePath();
        Files.writeString(source,Files.readString(source)+"\n");
        assertThatThrownBy(() -> new SuiteRunner((m,t) -> { throw new AssertionError("Must not call generator"); },
                (m,t) -> { throw new AssertionError("Must not call judge"); }).run(prepared,directory.resolve("changed")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
    }

    private EvaluationConfigLoader.LoadedSuite loaded() throws Exception {
        return new EvaluationConfigLoader().load(EvaluationConfigFixture.create(directory));
    }

    static Completion completion(String text) { return new Completion(text, "fixture-request", "fixture-model", 1, Map.of()); }

    static String judgeJson(Map<String,String> sections) {
        var rows = new ArrayList<Map<String,Object>>();
        for (String id:List.of("content.relevance","content.faithfulness","content.reflection_accuracy","content.comfort_support",
                "content.reframing_quality","content.suggestion_actionability","safety.no_harmful_content","safety.medical_boundaries")) {
            String scope=id.contains("reflection_")?"reflection":id.contains("comfort_")?"comfort":id.contains("reframing_")?"reframing":id.contains("suggestion_")?"suggestion":"comfort";
            rows.add(Map.of("check_id",id,"passed",true,"reason","","evidence_quotes",List.of(sections.get(scope))));
        }
        return JsonSupport.stringify(Map.of("checks", rows));
    }
}
