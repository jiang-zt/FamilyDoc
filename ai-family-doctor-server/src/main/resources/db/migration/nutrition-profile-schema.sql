-- Apply once to the intended application database before starting this feature.
-- Existing users are initialized lazily; new registrations create an empty profile.
CREATE TABLE IF NOT EXISTS user_nutrition_profile (
  user_id VARCHAR(64) PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 0,
  auto_update BOOLEAN NOT NULL DEFAULT TRUE,
  onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
  allergy_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
  life_stage VARCHAR(500) NULL,
  age_group VARCHAR(500) NULL,
  fields JSON NOT NULL DEFAULT (JSON_OBJECT()),
  last_turn_seq BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nutrition_profile_turn (
  turn_seq BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL UNIQUE,
  user_id VARCHAR(64) NOT NULL,
  profile_version BIGINT NOT NULL,
  auto_update BOOLEAN NOT NULL,
  profile_snapshot JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX ix_profile_turn_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
