package com.itzixi.evaluation;

import com.itzixi.evaluation.config.EvaluationConfigLoader;
import com.itzixi.evaluation.judge.ResponseJudge;
import com.itzixi.evaluation.model.EvaluationModels.*;
import com.itzixi.evaluation.runner.ResponseExecutor;
import com.itzixi.evaluation.runner.SuiteRunner;
import com.itzixi.evaluation.support.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static com.itzixi.evaluation.EvaluationPipelineTest.*;
import static com.itzixi.evaluation.model.EvaluationModels.STRUCTURE_IDS;

class CheckBasedJudgingTest {
    @TempDir Path directory;
    private PreparedSuite prepared() throws Exception {
        return SuiteRunner.prepare(new EvaluationConfigLoader().load(EvaluationConfigFixture.create(directory)));
    }

    @Test
    void oneFailedPointIsIndependentlyVisibleAndFailsTheCaseAndSuite() throws Exception {
        var prepared=prepared();
        var sections=new ResponseExecutor().extract(HTML);
        var output=JsonSupport.object(judgeJson(sections));
        var checks=JsonSupport.list(output.get("checks"));
        var failed=JsonSupport.asObject(checks.get(1));
        failed.put("passed",false);failed.put("reason","User background was invented.");
        var changed=new java.util.ArrayList<Object>(checks);changed.set(1,failed);output.put("checks",changed);
        var result=new SuiteRunner((m,t) -> completion(HTML),(m,t) -> completion(JsonSupport.stringify(output)))
                .run(prepared,directory.resolve("failed"));
        assertThat(result.status()).isEqualTo(Status.FAIL);
        var first=result.cases().getFirst();
        assertThat(first.checkResults()).hasSize(12);
        assertThat(first.checkResults()).filteredOn(c -> Boolean.FALSE.equals(c.passed()))
                .extracting(CheckResult::checkId).containsExactly("content.faithfulness");
        assertThat(Files.readString(result.runDirectory().resolve("report.html")))
                .contains("content.faithfulness","User background was invented.");
        var summary=JsonSupport.object(Files.readString(result.runDirectory().resolve("summary.json")));
        assertThat(JsonSupport.integer(summary,"checks_failed")).isEqualTo(1);
        assertThat(JsonSupport.asObject(JsonSupport.list(summary.get("task_results")).getFirst())).containsEntry("status","FAIL");
    }

    @Test
    void structureFailureRecordsEveryPointAndSkipsAllLlmChecks() throws Exception {
        var result=new SuiteRunner((m,t) -> completion("<p>Only a paragraph</p>"),(m,t) -> { throw new AssertionError("No judge call expected"); })
                .run(prepared(),directory.resolve("structure"));
        var checks=result.cases().getFirst().checkResults();
        assertThat(checks).hasSize(12);
        assertThat(checks).filteredOn(c -> c.evaluatorType().equals("code")).hasSize(4);
        assertThat(checks).filteredOn(c -> c.evaluatorType().equals("llm"))
                .allSatisfy(c -> { assertThat(c.passed()).isNull();assertThat(c.status()).isEqualTo(Status.SKIPPED); });
    }

    @Test
    void evaluatorFailureHasNullResultsAndNeverPretendsToBeContentFailure() throws Exception {
        var result=new SuiteRunner((m,t) -> completion(HTML),(m,t) -> { throw new com.itzixi.evaluation.client.ModelClient.ModelException("HTTP_404",false,false); })
                .run(prepared(),directory.resolve("judge-error"));
        var first=result.cases().getFirst();
        assertThat(first.status()).isEqualTo(Status.EVALUATION_ERROR);
        assertThat(first.checkResults()).filteredOn(c -> c.evaluatorType().equals("code")).allMatch(c -> Boolean.TRUE.equals(c.passed()));
        assertThat(first.checkResults()).filteredOn(c -> c.evaluatorType().equals("llm"))
                .allSatisfy(c -> { assertThat(c.passed()).isNull();assertThat(c.status()).isEqualTo(Status.EVALUATION_ERROR); });
    }

    @Test
    void judgeMustReturnExactlyTheRequestedIdsBooleansAndFailureReasons() throws Exception {
        var first=prepared().cases().getFirst();
        var sections=new ResponseExecutor().extract(HTML);
        String good=judgeJson(sections);
        var judge=new ResponseJudge();
        assertThat(judge.parse(good,sections,HTML,first.checks())).hasSize(8);
        for (String invalid:List.of(good.replace("\"passed\":true","\"passed\":\"true\""),
                good.replace("\"passed\":true","\"passed\":false"),
                good.replace("content.faithfulness","content.relevance"),
                good.replace("content.faithfulness","unknown"),"{\"checks\":[]}")) {
            assertThatThrownBy(() -> judge.parse(invalid,sections,HTML,first.checks())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void promptReferencesAndUnknownValidatorAreRejectedBeforeCalls() throws Exception {
        Path suite=EvaluationConfigFixture.create(directory);
        Files.delete(directory.resolve("prompts/judge/safety-boundaries.txt"));
        assertThatThrownBy(() -> SuiteRunner.prepare(new EvaluationConfigLoader().load(suite))).hasMessageContaining("safety-boundaries");
        Path secondSuite=EvaluationConfigFixture.create(directory);
        Path rubric=directory.resolve("tasks/rubrics/basic-qa-v2.json");
        Files.writeString(rubric,Files.readString(rubric).replace("\"validator_id\": \"slot_order\"","\"validator_id\": \"unknown_validator\""));
        Path finalSuite=secondSuite;
        assertThatThrownBy(() -> SuiteRunner.prepare(new EvaluationConfigLoader().load(finalSuite))).hasMessageContaining("validator");
    }

    @Test
    void onlyTaskSelectedDimensionsAreLoadedAndJudged() throws Exception {
        Path suite=EvaluationConfigFixture.create(directory);
        Path taskPath=directory.resolve("tasks/basic-qa.json");
        var task=JsonSupport.object(Files.readString(taskPath));
        var evaluation=JsonSupport.child(task,"evaluation");
        evaluation.put("checks",JsonSupport.list(evaluation.get("checks")).stream().filter(id -> !id.toString().startsWith("safety.")).toList());
        task.put("evaluation",evaluation);Files.writeString(taskPath,JsonSupport.stringify(task));
        Files.delete(directory.resolve("prompts/judge/safety-boundaries.txt"));
        var prepared=SuiteRunner.prepare(new EvaluationConfigLoader().load(suite));
        var first=prepared.cases().getFirst();
        var sections=new ResponseExecutor().extract(HTML);
        var payload=JsonSupport.object(new ResponseJudge().messages(first.testCase(),sections,HTML,first.entry().rubric(),first.checks()).getLast().content());
        assertThat(JsonSupport.child(payload,"dimension_instructions")).containsOnlyKeys("content");
        assertThat(JsonSupport.list(payload.get("checks"))).hasSize(6);
        var output=JsonSupport.object(judgeJson(sections));
        output.put("checks",JsonSupport.list(output.get("checks")).stream()
                .filter(row -> !JsonSupport.text(JsonSupport.asObject(row),"check_id").startsWith("safety.")).toList());
        var result=new SuiteRunner((m,t) -> completion(HTML),(m,t) -> completion(JsonSupport.stringify(output)))
                .run(prepared,directory.resolve("content-only"));
        assertThat(result.status()).isEqualTo(Status.PASS);
        assertThat(result.cases().getFirst().checkResults()).hasSize(10);
    }

    @Test
    void fullAnswerSafetyCanCiteNoteButTypeCheckCannotCiteAnotherSection() throws Exception {
        var first=prepared().cases().getFirst();
        var sections=new ResponseExecutor().extract(HTML);
        var output=JsonSupport.object(judgeJson(sections));
        var rows=new java.util.ArrayList<Object>(JsonSupport.list(output.get("checks")));
        var safety=JsonSupport.asObject(rows.get(6));
        safety.put("evidence_quotes",List.of("A note outside the four sections."));rows.set(6,safety);output.put("checks",rows);
        assertThat(new ResponseJudge().parse(JsonSupport.stringify(output),sections,HTML,first.checks())).hasSize(8);
        var reflection=JsonSupport.asObject(rows.get(2));
        reflection.put("evidence_quotes",List.of("You are not alone."));rows.set(2,reflection);
        assertThatThrownBy(() -> new ResponseJudge().parse(JsonSupport.stringify(output),sections,HTML,first.checks()))
                .hasMessageContaining("scope");
    }

    @Test
    void codeOnlySelectionPassesWithoutPretendingThatLlmQualityWasEvaluated() throws Exception {
        Path suite=EvaluationConfigFixture.create(directory);
        Path taskPath=directory.resolve("tasks/basic-qa.json");
        var task=JsonSupport.object(Files.readString(taskPath));
        var evaluation=JsonSupport.child(task,"evaluation");
        evaluation.put("checks",STRUCTURE_IDS);task.put("evaluation",evaluation);
        Files.writeString(taskPath,JsonSupport.stringify(task));
        var prepared=SuiteRunner.prepare(new EvaluationConfigLoader().load(suite));
        var result=new SuiteRunner((m,t) -> completion(HTML),(m,t) -> { throw new AssertionError("No LLM judge selected"); })
                .run(prepared,directory.resolve("code-only"));
        assertThat(result.status()).isEqualTo(Status.PASS);assertThat(result.judgeCalls()).isZero();
        String report=Files.readString(result.runDirectory().resolve("report.html"));
        assertThat(report).contains("测评结果：PASS", "2 / 2 条用例通过", "0 / 2");
    }

    @Test
    void fixedCodeChecksCannotBeReroutedToAnLlm() throws Exception {
        Path suite=EvaluationConfigFixture.create(directory);
        Path rubric=directory.resolve("tasks/rubrics/basic-qa-v2.json");
        var document=JsonSupport.object(Files.readString(rubric));
        var rows=new java.util.ArrayList<Object>(JsonSupport.list(document.get("rules")));
        var first=JsonSupport.asObject(rows.getFirst());
        first.put("evaluator_type","llm");first.put("dimension","content");
        first.put("prompt_ref","../../prompts/judge/content-quality.txt");rows.set(0,first);document.put("rules",rows);
        Files.writeString(rubric,JsonSupport.stringify(document));
        assertThatThrownBy(() -> SuiteRunner.prepare(new EvaluationConfigLoader().load(suite)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("structural");
    }

    @Test
    void fixedOutputContractCannotBeWeakenedByAnEditableFlag() throws Exception {
        Path suite=EvaluationConfigFixture.create(directory);
        Path taskPath=directory.resolve("tasks/basic-qa.json");
        var task=JsonSupport.object(Files.readString(taskPath));
        var config=JsonSupport.child(task,"executor_config");
        var contract=JsonSupport.child(config,"output_contract");contract.put("require_order",false);
        config.put("output_contract",contract);task.put("executor_config",config);
        Files.writeString(taskPath,JsonSupport.stringify(task));
        assertThatThrownBy(() -> SuiteRunner.prepare(new EvaluationConfigLoader().load(suite)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("require_order");
    }
}
