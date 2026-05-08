package com.itzixi.apitest;

import com.itzixi.apitest.support.ApiTestBase;
import com.itzixi.apitest.support.AuthSession;
import com.itzixi.apitest.support.AuthSupport;
import com.itzixi.apitest.support.TestConfig;
import com.itzixi.scoring.MedicalAnswerScorer;
import com.itzixi.scoring.MedicalEvalCase;
import com.itzixi.scoring.MedicalEvalCaseLoader;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MedicalEvalTest extends ApiTestBase {

    private final MedicalAnswerScorer scorer = new MedicalAnswerScorer();
    private final MedicalEvalCaseLoader caseLoader = new MedicalEvalCaseLoader();

    @Test
    void medicalAnswersPassRuleBasedEval() {
        assumeTrue(
                TestConfig.evalTestsEnabled(),
                "Medical eval is disabled because it calls the model layer. Enable with -Dapi.eval.enabled=true."
        );

        List<MedicalEvalCase> cases = caseLoader.loadCases();
        assertThat(cases).isNotEmpty();

        AuthSession user = AuthSupport.registerRandomUser();
        List<EvalResult> failures = new ArrayList<>();

        for (MedicalEvalCase medicalCase : cases) {
            String answer = given()
                    .header("Authorization", "Bearer " + user.token())
                    .contentType(ContentType.JSON)
                    .body(Map.of("message", medicalCase.question()))
                    .when()
                    .post("/chat")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();

            MedicalAnswerScorer.ScoreResult scoreResult = scorer.score(medicalCase.question(), answer);
            boolean pass = scoreResult.pass() && Objects.equals(medicalCase.ruleId(), scoreResult.matchedRuleId());
            EvalResult result = new EvalResult(
                    medicalCase.id(),
                    pass,
                    scoreResult.totalScore(),
                    scoreResult.matchedRuleId(),
                    scoreResult.issues()
            );

            System.out.printf(
                    "[medical-eval] id=%s category=%s rule=%s matchedRule=%s pass=%s score=%d answer=%s issues=%s%n",
                    medicalCase.id(),
                    medicalCase.category(),
                    medicalCase.ruleId(),
                    result.matchedRuleId(),
                    result.pass(),
                    result.score(),
                    abbreviate(answer, 120),
                    result.issues()
            );
            if (!result.pass()) {
                failures.add(result);
            }
        }

        assertThat(failures)
                .as("Medical eval failures")
                .isEmpty();
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private record EvalResult(
            String caseId,
            boolean pass,
            int score,
            String matchedRuleId,
            List<String> issues
    ) {
    }
}
