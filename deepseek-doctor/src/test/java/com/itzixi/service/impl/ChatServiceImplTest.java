package com.itzixi.service.impl;

import com.itzixi.bean.ChatMetric;
import com.itzixi.scoring.MedicalAnswerScorer;
import com.itzixi.service.ChatMetricService;
import com.itzixi.service.ChatRecordService;
import com.itzixi.utils.DashscopeChatClient;
import com.itzixi.utils.PromptLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private DashscopeChatClient dashscopeChatClient;

    @Mock
    private ChatRecordService chatRecordService;

    @Mock
    private ChatMetricService chatMetricService;

    @Mock
    private PromptLoader promptLoader;

    @Mock
    private MedicalAnswerScorer medicalAnswerScorer;

    @InjectMocks
    private ChatServiceImpl chatService;

    @Test
    void chatShouldCallModelWithSystemPromptAndSaveScoredMetric() {
        when(promptLoader.getSystemPrompt()).thenReturn("你是家庭医生助手");
        when(promptLoader.getPromptVersion()).thenReturn("prompt-v1");
        when(dashscopeChatClient.getModel()).thenReturn("qwen-test");
        when(dashscopeChatClient.chat(any())).thenReturn("```html\n<p>请注意休息，并及时就医。</p>\n```");
        when(medicalAnswerScorer.score("我头疼怎么办？", "\n<p>请注意休息，并及时就医。</p>\n"))
                .thenReturn(new MedicalAnswerScorer.ScoreResult(
                        88,
                        35,
                        25,
                        18,
                        7,
                        3,
                        true,
                        "headache_support",
                        List.of("缺少进一步观察或补充信息引导")
                ));

        String answer = chatService.chat("alice", "我头疼怎么办？");

        assertThat(answer).isEqualTo("\n<p>请注意休息，并及时就医。</p>\n");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(dashscopeChatClient).chat(messagesCaptor.capture());
        assertThat(messagesCaptor.getValue())
                .containsExactly(
                        Map.of("role", "system", "content", "你是家庭医生助手"),
                        Map.of("role", "user", "content", "我头疼怎么办？")
                );

        ArgumentCaptor<ChatMetric> metricCaptor = ArgumentCaptor.forClass(ChatMetric.class);
        verify(chatMetricService).saveMetric(metricCaptor.capture());
        ChatMetric metric = metricCaptor.getValue();
        assertThat(metric.getUserName()).isEqualTo("alice");
        assertThat(metric.getQuestion()).isEqualTo("我头疼怎么办？");
        assertThat(metric.getModel()).isEqualTo("qwen-test");
        assertThat(metric.getPromptVersion()).isEqualTo("prompt-v1");
        assertThat(metric.getFirstTokenMs()).isNotNegative();
        assertThat(metric.getTotalMs()).isNotNegative();
        assertThat(metric.getAvgTokenIntervalMs()).isNull();
        assertThat(metric.getOutputChars()).isEqualTo(answer.length());
        assertThat(metric.getOutputTokens()).isEqualTo(1);
        assertThat(metric.getAccuracyScore()).isEqualTo(88);
        assertThat(metric.getMatchedRuleId()).isEqualTo("headache_support");
        assertThat(metric.getSafetyScore()).isEqualTo(35);
        assertThat(metric.getFactualScore()).isEqualTo(25);
        assertThat(metric.getCoverageScore()).isEqualTo(18);
        assertThat(metric.getSemanticScore()).isEqualTo(7);
        assertThat(metric.getExpressionScore()).isEqualTo(3);
        assertThat(metric.getScorePass()).isTrue();
        assertThat(metric.getScoreIssues()).isEqualTo("缺少进一步观察或补充信息引导");
        assertThat(metric.getCreatedAt()).isNotNull();

        verify(chatRecordService, never()).saveChatRecord(any(), any(), any());
    }

    @Test
    void chatShouldStillReturnAnswerWhenMetricSaveFails() {
        when(promptLoader.getSystemPrompt()).thenReturn("system");
        when(promptLoader.getPromptVersion()).thenReturn("prompt-v1");
        when(dashscopeChatClient.getModel()).thenReturn("qwen-test");
        when(dashscopeChatClient.chat(any())).thenReturn("<p>已收到。</p>");
        when(medicalAnswerScorer.score("你好", "<p>已收到。</p>"))
                .thenReturn(new MedicalAnswerScorer.ScoreResult(
                        72,
                        30,
                        20,
                        12,
                        6,
                        4,
                        true,
                        null,
                        List.of()
                ));
        doThrow(new IllegalStateException("database unavailable"))
                .when(chatMetricService)
                .saveMetric(any(ChatMetric.class));

        String answer = chatService.chat("bob", "你好");

        assertThat(answer).isEqualTo("<p>已收到。</p>");
        verify(chatMetricService).saveMetric(any(ChatMetric.class));
    }
}
