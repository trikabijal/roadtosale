ALTER TABLE ai_assessments ADD COLUMN ai_provider VARCHAR(50);
ALTER TABLE ai_assessments ADD COLUMN input_tokens INTEGER;
ALTER TABLE ai_assessments ADD COLUMN output_tokens INTEGER;
ALTER TABLE ai_assessments ADD COLUMN latency_ms BIGINT;

UPDATE ai_assessments SET ai_provider = 'ANTHROPIC' WHERE ai_provider IS NULL;
