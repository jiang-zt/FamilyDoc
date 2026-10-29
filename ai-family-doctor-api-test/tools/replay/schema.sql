-- 仅用于独立回放库的新建初始化；不对开发/生产库执行。
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
  message_seq INT NOT NULL DEFAULT 0,
  INDEX idx_chat_record_family_member (family_member),
  INDEX idx_chat_record_chat_time (chat_time)
);

CREATE TABLE IF NOT EXISTS chat_metric (
  id VARCHAR(255) PRIMARY KEY,
  user_name VARCHAR(100) NULL,
  question TEXT NULL,
  request_id VARCHAR(36) NULL,
  status VARCHAR(20) NULL,
  error_code VARCHAR(50) NULL,
  context_message_count INT NULL,
  context_chars INT NULL,
  scoring_version VARCHAR(50) NULL,
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
  UNIQUE INDEX uk_chat_metric_request_id (request_id),
  INDEX idx_chat_metric_user_name (user_name),
  INDEX idx_chat_metric_created_at (created_at)
);
