-- 确认运行时冻结每个实际模型的价格版本；新价格发布不得影响已确认运行。
ALTER TABLE run_cost_limits ADD COLUMN IF NOT EXISTS model_price_versions_json VARCHAR(4000);
