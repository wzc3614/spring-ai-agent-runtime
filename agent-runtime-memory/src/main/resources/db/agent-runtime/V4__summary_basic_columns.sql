-- =============================================================================
-- Agent Runtime Harness - basic summary columns
-- Target: PostgreSQL
-- Migration: Flyway V4
-- 为基础版 LLM 摘要补充元数据列：summarized_up_to_seq 标识该摘要已覆盖的 message seq 边界。
-- sections 列保留为通用内容 JSON（当前存一段式摘要，后续可演进）。
-- =============================================================================

ALTER TABLE agent_summary_snapshot
    ADD COLUMN summarized_up_to_seq BIGINT NOT NULL DEFAULT 0;
