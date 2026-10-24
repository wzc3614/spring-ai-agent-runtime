-- =============================================================================
-- Agent Runtime Harness - V6: skill metadata
-- Target: PostgreSQL
-- Instruction body lives in instruction and is never selected by revision queries.
-- Writers must bump content_hash and updated_at when metadata changes.
-- Instruction-only edits are picked up on the next activation without a hash change.
-- =============================================================================
CREATE TABLE agent_skill (
    skill_id      VARCHAR(64)  PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    description   TEXT         NOT NULL,
    not_for       TEXT,
    triggers      TEXT,
    tool_refs     TEXT,
    instruction   TEXT,
    version       VARCHAR(64),
    content_hash  VARCHAR(64)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT true,
    meta          TEXT,
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);
