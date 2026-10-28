package com.itzixi.service.impl;
import com.itzixi.bean.ChatMetric;
import com.itzixi.scoring.MedicalAnswerScorer;
import com.itzixi.service.ChatMetricService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatMetricRecorder {
    @Resource private ChatMetricService chatMetricService;
    @Resource private MedicalAnswerScorer scorer;
    public void record(ChatMetric metric, List<Map<String,String>> history, String answer) {
        metric.setCreatedAt(LocalDateTime.now());
        metric.setOutputChars(answer.length());
        // 沿用原有估算口径，并非模型精确 token 使用量。
        metric.setOutputTokens(answer.isBlank() ? 0 : answer.trim().split("\\s+").length);
        metric.setScoringVersion("context-rules-v1");
        if ("SUCCESS".equals(metric.getStatus())) {
            try {
                var score = scorer.score(history, metric.getQuestion(), answer);
                metric.setAccuracyScore(score.totalScore());
                metric.setMatchedRuleId(score.matchedRuleId());
                metric.setSafetyScore(score.safetyScore());
                metric.setFactualScore(score.factualScore());
                metric.setCoverageScore(score.coverageScore());
                metric.setSemanticScore(score.semanticScore());
                metric.setExpressionScore(score.expressionScore());
                metric.setScorePass(score.pass());
                metric.setScoreIssues(String.join(" | ",score.issues()));
            } catch (Exception ex) {
                metric.setScoreIssues("评分执行失败，未生成评分");
                log.warn("评分失败 requestId={}",metric.getRequestId(),ex);
            }
        }
        try { chatMetricService.saveMetric(metric); }
        catch (Exception ex) { log.error("问题与指标保存失败 requestId={}",metric.getRequestId(),ex); }
    }
}
