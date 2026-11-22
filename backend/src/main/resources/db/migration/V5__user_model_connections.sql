-- BYOK：用户自带的模型连接。密钥只保存 AES-256-GCM 密文，列表只回显掩码。
CREATE TABLE IF NOT EXISTS user_model_connections (
  id VARCHAR(36) PRIMARY KEY,
  owner_user_id VARCHAR(36) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  provider_type VARCHAR(40) NOT NULL,
  base_url VARCHAR(500) NOT NULL,
  model_name VARCHAR(120) NOT NULL,
  encrypted_api_key TEXT NOT NULL,
  key_masked VARCHAR(60) NOT NULL,
  enabled BOOLEAN DEFAULT TRUE NOT NULL,
  is_default BOOLEAN DEFAULT FALSE NOT NULL,
  input_price_per_1m DOUBLE,
  output_price_per_1m DOUBLE,
  currency VARCHAR(10) DEFAULT 'CNY' NOT NULL,
  last_test_status VARCHAR(20),
  last_test_message VARCHAR(500),
  last_tested_at VARCHAR(40),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_model_connections_owner
  ON user_model_connections(owner_user_id);

-- 任务冻结创建时选择的用户模型连接；为空表示沿用内置 mock / deepseek 模式。
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS model_connection_id VARCHAR(36);
