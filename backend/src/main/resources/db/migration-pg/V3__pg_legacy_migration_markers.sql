-- PostgreSQL 历史迁移标记补齐（幂等）。
--
-- H2 的 V2__legacy_upgrade.sql 在升级旧单机数据时写入了三条 workbench_schema_migrations 标记；
-- PostgreSQL 基线是一条独立的演进链，历史数据升级不会执行，因此这些标记从未写入。
-- 这里按幂等方式补齐，保证同一份代码在两种数据库上读到的迁移历史一致；不修改任何历史脚本。

INSERT INTO workbench_schema_migrations (migration_id)
VALUES ('tool_budgets_v2')
ON CONFLICT (migration_id) DO NOTHING;

INSERT INTO workbench_schema_migrations (migration_id)
VALUES ('workflow_check_v1')
ON CONFLICT (migration_id) DO NOTHING;

INSERT INTO workbench_schema_migrations (migration_id)
VALUES ('workflow_plan_v1')
ON CONFLICT (migration_id) DO NOTHING;
