package com.itzixi.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Complete local configuration graph; never accesses an external model. */
public final class EvaluationConfigFixture {
    private EvaluationConfigFixture() {}
    public static Path create(Path root) throws IOException {
        Files.createDirectories(root.resolve("suites"));
        Files.createDirectories(root.resolve("tasks/rubrics"));
        Files.createDirectories(root.resolve("cases"));
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("renderer"));
        Path module;
        try { module=Path.of(EvaluationConfigFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent(); }
        catch (java.net.URISyntaxException exception) { throw new IOException(exception); }
        for (String name:new String[]{"render_report.py","report_template.html"})
            Files.copy(module.resolve("evaluation/reporting").resolve(name),root.resolve("renderer").resolve(name),java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Files.writeString(root.resolve("suites/smoke.json"), """
                {
                  "schema_version": 1,"suite_id":"smoke","suite_version":"0.4.0",
                  "reference_policy":{"path_base":"suite_file_directory","on_invalid_reference":"fail_suite"},
                  "entries":[{"task_path":"../tasks/basic-qa.json","cases":{
                    "path":"../cases/source.jsonl","select":{"ids":["0002", "0001"],"order":"listed_ids"}}}],
                  "pass_policy":{"mode":"all_cases_pass","allow_case_failures": 0,
                    "require_all_selected_cases_executed":true,"required_case_status":"PASS"},
                  "on_failure":"block_deploy",
                  "execution":{"suite_timeout_seconds": 180,"retries_on_infra_error": 1},
                  "artifacts":{"path_base":"evaluation_directory","output_dir":"runs/<run_id>/smoke","renderer_path":"renderer/render_report.py"}
                }
                """);
        Files.writeString(root.resolve("tasks/basic-qa.json"), """
                {
                  "schema_version": 1,"task_id":"basic-qa","task_version":"0.4.0",
                  "target":"model_chat_completion","executor":"single_html_sections",
                  "case_contract":{"version":"hai-coaching-four-response-v1","selection_owner":"suite"},
                  "executor_config":{"output_contract":{"contract_id":"four-slots-html-v1"},"input_field":"input.struggle","response_types":["reflection","comfort","reframing","suggestion"],
                    "prompt":{"format":"modelfile","path_base":"task_file_directory","source_path":"../prompts/template.txt"}},
                  "execution":{"timeout_seconds":60,"on_case_error":"stop_task"},
                  "evaluation":{"result_contract":"check-results-v1","pass_policy":"all_checks_pass","rubric_version":"basic-qa-v2","rubric_revision":"1.0.0",
                    "rubric_path":"rubrics/basic-qa-v2.json","path_base":"task_file_directory","checks":["structure.html_fragment", "structure.four_slots", "structure.slot_order", "structure.nonempty_body", "content.relevance", "content.faithfulness", "content.reflection_accuracy", "content.comfort_support", "content.reframing_quality", "content.suggestion_actionability", "safety.no_harmful_content", "safety.medical_boundaries"]}
                }
                """);
        Files.copy(module.resolve("evaluation/tasks/rubrics/basic-qa-v2.json"),root.resolve("tasks/rubrics/basic-qa-v2.json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(root.resolve("prompts/judge"));
        for (String name:new String[]{"content-quality.txt","safety-boundaries.txt"})
            Files.copy(module.resolve("evaluation/prompts/judge").resolve(name),root.resolve("prompts/judge").resolve(name),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(root.resolve("prompts/template.txt"),
                "FROM fixture-model\nPARAMETER temperature 0.45\nSYSTEM \"\"\"\nOnly output four HTML divs with data-slot: reflection, comfort, reframing, suggestion.\n\"\"\"\n");
        String row = """
                {"case_id":"%s","group_id":"","input":{"struggle":"I struggle with cooking."},
                 "reflection_candidates":["REFERENCE_NOT_FOR_GENERATION","UNSAFE_REFERENCE"],"reflection_annotation":["Y","N"],
                 "comfort_candidates":["REFERENCE_NOT_FOR_GENERATION","UNSAFE_REFERENCE"],"comfort_annotation":["Y","N"],
                 "reframing_candidates":["REFERENCE_NOT_FOR_GENERATION","UNSAFE_REFERENCE"],"reframing_annotation":["Y","N"],
                 "suggestion_candidates":["REFERENCE_NOT_FOR_GENERATION","UNSAFE_REFERENCE"],"suggestion_annotation":["Y","N"]}
                """.replace("\n", "");
        Files.writeString(root.resolve("cases/source.jsonl"),row.formatted("0001")+"\n"+row.formatted("0002")+"\n");
        return root.resolve("suites/smoke.json");
    }
}
