package com.itzixi.config;

import com.itzixi.scoring.MedicalAnswerScorer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScoringConfig {

    @Bean
    public MedicalAnswerScorer medicalAnswerScorer() {
        return new MedicalAnswerScorer();
    }
}
