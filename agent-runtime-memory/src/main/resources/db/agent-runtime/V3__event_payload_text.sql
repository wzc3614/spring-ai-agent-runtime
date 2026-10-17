-- =============================================================================
-- Agent Runtime Harness - V3: event payload column jsonb -> text
-- 事件载荷是异构的（FINAL_REPLY 纯文本、TOOL_INPUT JSON、TOOL_OUTPUT 任意），
-- 用 text 比 jsonb 更合适，避免非 JSON 文本写入失败。
-- =============================================================================
ALTER TABLE agent_runtime_obs.event_record ALTER COLUMN payload TYPE text USING payload::text;
