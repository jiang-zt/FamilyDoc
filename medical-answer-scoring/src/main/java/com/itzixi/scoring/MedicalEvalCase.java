package com.itzixi.scoring;

public record MedicalEvalCase(
        String id,
        String category,
        String ruleId,
        String question
) {
}
