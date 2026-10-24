-- Tool results stay in PostgreSQL. Original JSON lives on the message row.
-- agent_spill_record pointed at object storage and has no writer.

ALTER TABLE agent_message ADD COLUMN payload JSONB;
ALTER TABLE agent_message ADD COLUMN compaction_class VARCHAR(16);

DROP TABLE IF EXISTS agent_spill_record;

CREATE INDEX idx_agent_message_retained
    ON agent_message (session_id, seq)
    WHERE compaction_class = 'RETAIN';
