-- 手动执行的增量迁移：新增共享会话表；不修改或删除旧聊天、指标和账号数据。
-- 不由 Spring Boot 自动执行。新库也需在账号/指标初始化后执行本文件。
CREATE TABLE IF NOT EXISTS chat_conversation (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id VARCHAR(255) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  next_seq BIGINT NOT NULL DEFAULT 0,
  active_request_id VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_chat_conversation_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS chat_generation (
  request_id VARCHAR(64) NOT NULL PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(255) NOT NULL,
  client_request_id VARCHAR(64) NOT NULL,
  question TEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  owner_instance VARCHAR(64) NOT NULL,
  lease_until DATETIME(3) NOT NULL,
  error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_chat_generation_client (user_id, client_request_id),
  KEY idx_chat_generation_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS chat_shared_message (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  seq BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  UNIQUE KEY uk_chat_shared_message_seq (conversation_id, seq),
  KEY idx_chat_shared_message_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
