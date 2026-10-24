CREATE TABLE IF NOT EXISTS app_user (
  id VARCHAR(255) PRIMARY KEY,
  username VARCHAR(100) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  created_at DATETIME NULL,
  updated_at DATETIME NULL
);

CREATE TABLE IF NOT EXISTS chat_record (
  id VARCHAR(255) PRIMARY KEY,
  content TEXT NULL,
  chat_type VARCHAR(50) NULL,
  chat_time DATETIME NULL,
  family_member VARCHAR(100) NULL,
  INDEX idx_chat_record_family_member (family_member),
  INDEX idx_chat_record_chat_time (chat_time)
);

CREATE TABLE IF NOT EXISTS chat_metric (
  id VARCHAR(255) PRIMARY KEY,
  user_name VARCHAR(100) NULL,
  question TEXT NULL,
  model VARCHAR(100) NULL,
  prompt_version VARCHAR(50) NULL,
  first_token_ms BIGINT NULL,
  total_ms BIGINT NULL,
  avg_token_interval_ms DECIMAL(10,2) NULL,
  output_chars INT NULL,
  output_tokens INT NULL,
  accuracy_score INT NULL,
  matched_rule_id VARCHAR(100) NULL,
  safety_score INT NULL,
  factual_score INT NULL,
  coverage_score INT NULL,
  semantic_score INT NULL,
  expression_score INT NULL,
  score_pass TINYINT(1) NULL,
  score_issues TEXT NULL,
  created_at DATETIME NULL,
  INDEX idx_chat_metric_user_name (user_name),
  INDEX idx_chat_metric_created_at (created_at)
);
