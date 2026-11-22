-- Phase 4：MCP Server 注册表与缓存工具清单。凭据仅保存 AES-256-GCM 密文。
CREATE TABLE IF NOT EXISTS user_mcp_servers (
  id VARCHAR(36) PRIMARY KEY,
  owner_scope_key VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  slug VARCHAR(64) NOT NULL,
  transport VARCHAR(30) NOT NULL,
  command VARCHAR(200),
  args_json VARCHAR(2000) DEFAULT '[]' NOT NULL,
  endpoint_url VARCHAR(500),
  headers_cipher CLOB,
  env_cipher CLOB,
  enabled BOOLEAN DEFAULT TRUE NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  UNIQUE(owner_scope_key, slug)
);

CREATE TABLE IF NOT EXISTS user_mcp_tools (
  id VARCHAR(36) PRIMARY KEY,
  server_id VARCHAR(36) NOT NULL REFERENCES user_mcp_servers(id),
  name VARCHAR(120) NOT NULL,
  description VARCHAR(1000),
  input_schema_json CLOB,
  risk_level VARCHAR(20) DEFAULT 'HIGH' NOT NULL,
  enabled BOOLEAN DEFAULT TRUE NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  UNIQUE(server_id, name)
);

CREATE INDEX IF NOT EXISTS idx_user_mcp_servers_owner ON user_mcp_servers(owner_scope_key, enabled);
CREATE INDEX IF NOT EXISTS idx_user_mcp_tools_server ON user_mcp_tools(server_id, enabled);
