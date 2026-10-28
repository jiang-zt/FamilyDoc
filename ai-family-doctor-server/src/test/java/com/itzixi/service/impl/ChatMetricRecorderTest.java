package com.itzixi.service.impl;
import com.itzixi.bean.ChatMetric;
import com.itzixi.scoring.MedicalAnswerScorer;
import com.itzixi.service.ChatMetricService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChatMetricRecorderTest {
    final ChatMetricRecorder recorder=new ChatMetricRecorder();
    final ChatMetricService store=mock(ChatMetricService.class);
    final MedicalAnswerScorer scorer=mock(MedicalAnswerScorer.class);
    ChatMetricRecorderTest() { ReflectionTestUtils.setField(recorder,"chatMetricService",store); ReflectionTestUtils.setField(recorder,"scorer",scorer); }
    ChatMetric metric(String status) { var m=new ChatMetric(); m.setQuestion("完整问题");m.setStatus(status);return m; }
    @Test void failedRequestKeepsQuestionAndHasNoScores() {
        var m=metric("CANCELLED"); recorder.record(m,List.of(),"partial");
        verifyNoInteractions(scorer);verify(store).saveMetric(m);
        assertThat(m.getQuestion()).isEqualTo("完整问题"); assertThat(m.getAccuracyScore()).isNull();assertThat(m.getScorePass()).isNull();
    }
    @Test void scoringFailureStillSavesQuestionAndMetrics() {
        when(scorer.score(anyList(),anyString(),anyString())).thenThrow(new IllegalStateException("score"));
        var m=metric("SUCCESS");recorder.record(m,List.of(),"answer");
        verify(store).saveMetric(m);assertThat(m.getAccuracyScore()).isNull();assertThat(m.getScoreIssues()).contains("评分执行失败");
    }
    @Test void successStoresAllScoreDimensionsAndDbFailureDoesNotThrow() {
        when(scorer.score(anyList(),anyString(),anyString())).thenReturn(new MedicalAnswerScorer.ScoreResult(88,35,25,18,7,3,true,"rule",List.of("issue")));
        var m=metric("SUCCESS");doThrow(new IllegalStateException("db")).when(store).saveMetric(m);
        assertThatCode(() -> recorder.record(m,List.of(),"hello world")).doesNotThrowAnyException();
        assertThat(m.getAccuracyScore()).isEqualTo(88);assertThat(m.getSafetyScore()).isEqualTo(35);
        assertThat(m.getFactualScore()).isEqualTo(25);assertThat(m.getCoverageScore()).isEqualTo(18);
        assertThat(m.getSemanticScore()).isEqualTo(7);assertThat(m.getExpressionScore()).isEqualTo(3);
        assertThat(m.getMatchedRuleId()).isEqualTo("rule");assertThat(m.getScorePass()).isTrue();
        assertThat(m.getOutputTokens()).isEqualTo(2);assertThat(m.getScoringVersion()).isEqualTo("context-rules-v1");
    }
}
