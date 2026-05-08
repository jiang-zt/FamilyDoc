ALTER TABLE chat_metric
ADD COLUMN matched_rule_id VARCHAR(100) NULL COMMENT '命中的评分规则ID' AFTER accuracy_score,
ADD COLUMN safety_score INT NULL COMMENT '安全维度得分' AFTER matched_rule_id,
ADD COLUMN factual_score INT NULL COMMENT '事实维度得分' AFTER safety_score,
ADD COLUMN coverage_score INT NULL COMMENT '覆盖维度得分' AFTER factual_score,
ADD COLUMN semantic_score INT NULL COMMENT '语义维度得分' AFTER coverage_score,
ADD COLUMN expression_score INT NULL COMMENT '表达维度得分' AFTER semantic_score,
ADD COLUMN score_pass TINYINT(1) NULL COMMENT '评分是否通过' AFTER expression_score,
ADD COLUMN score_issues TEXT NULL COMMENT '评分问题明细' AFTER score_pass;
