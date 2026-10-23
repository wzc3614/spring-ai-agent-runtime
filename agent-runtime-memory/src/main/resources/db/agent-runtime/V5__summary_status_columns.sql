-- =============================================================================
-- Agent Runtime Harness - summary status columns
-- Target: PostgreSQL
-- Migration: Flyway V5
-- 为压缩管道降级与回退补充可观测列：status 标识本次压缩结果（SUCCESS/PARTIAL/FAILED），
-- error_message 记录失败原因。历史行默认 SUCCESS，保持兼容。
-- =============================================================================

ALTER TABLE agent_summary_snapshot
    ADD COLUMN status         VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    ADD COLUMN error_message  TEXT;
