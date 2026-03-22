package com.itzixi.bean;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@ToString
public class ChatMetric {

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
    private LocalDateTime createdAt;
}
