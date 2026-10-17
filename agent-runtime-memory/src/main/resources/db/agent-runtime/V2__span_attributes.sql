-- =============================================================================
-- Agent Runtime Harness - V2: add attributes to span
-- Target: PostgreSQL
-- =============================================================================
ALTER TABLE agent_runtime_obs.span ADD COLUMN IF NOT EXISTS attributes JSONB;
