package com.itzixi.bean;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@ToString
@TableName("chat_metric")
public class ChatMetric {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userName;
    private String question;
    private String model;
    private String promptVersion;
    private Long firstTokenMs;
    private Long totalMs;
    private Double avgTokenIntervalMs;
    private Integer outputChars;
    private Integer outputTokens;
    private Integer accuracyScore;
    private String matchedRuleId;
    private Integer safetyScore;
    private Integer factualScore;
    private Integer coverageScore;
    private Integer semanticScore;
    private Integer expressionScore;
    private Boolean scorePass;
    private String scoreIssues;
    private LocalDateTime createdAt;
}
