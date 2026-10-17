-- =============================================================================
-- Agent Runtime Harness - initial schema
-- Target: PostgreSQL
-- Naming convention: snake_case, all tables prefixed with agent_
-- Migration: Flyway V1
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Session
-- -----------------------------------------------------------------------------
CREATE TABLE agent_session (
    session_id      VARCHAR(64)  PRIMARY KEY,
    agent_name      VARCHAR(128) NOT NULL,
    user_id         VARCHAR(128),
    status          VARCHAR(32)  NOT NULL,            -- ACTIVE / ENDED / CANCELLED / TIMED_OUT
    version         BIGINT       NOT NULL DEFAULT 0, -- optimistic lock for cross-instance resume
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    last_active_at  TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_session_user ON agent_session (user_id, last_active_at);

-- -----------------------------------------------------------------------------
-- Message (append-only; compacted = true once replaced by an evidence placeholder)
-- -----------------------------------------------------------------------------
CREATE TABLE agent_message (
    message_id   VARCHAR(64)  PRIMARY KEY,
    session_id   VARCHAR(64)  NOT NULL REFERENCES agent_session (session_id),
    seq          BIGINT       NOT NULL,               -- monotonic within a session, guarantees order
    role         VARCHAR(16)  NOT NULL,               -- system / user / assistant / tool
    content      TEXT,
    compacted    BOOLEAN      NOT NULL DEFAULT false,
    evidence_id  VARCHAR(64),                          -- evidence pointer after micro-compaction
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (session_id, seq)
);
CREATE INDEX idx_agent_message_session_seq ON agent_message (session_id, seq);

-- -----------------------------------------------------------------------------
-- Summary snapshot (nine-section, versioned to support "summary on summary")
-- -----------------------------------------------------------------------------
CREATE TABLE agent_summary_snapshot (
    summary_id  VARCHAR(64)  PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL REFERENCES agent_session (session_id),
    version     INT          NOT NULL,
    sections    JSONB        NOT NULL,                 -- nine sections P0-P3
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (session_id, version)
);

-- -----------------------------------------------------------------------------
-- Tool call (attached to the assistant message that requested it)
--   status: PENDING / SUCCESS / FAILED / SPILLED / AWAITING_APPROVAL / AMBIGUOUS
--   failure_category: TRANSIENT / TERMINAL / AMBIGUOUS
-- -----------------------------------------------------------------------------
CREATE TABLE agent_tool_call (
    tool_call_id      VARCHAR(64)  PRIMARY KEY,
    message_id        VARCHAR(64)  NOT NULL REFERENCES agent_message (message_id),
    tool_name         VARCHAR(128) NOT NULL,
    arguments         JSONB,
    result            JSONB,
    status            VARCHAR(32)  NOT NULL,
    idempotent        BOOLEAN      NOT NULL DEFAULT false,
    risk_level        VARCHAR(16),                    -- LOW / MEDIUM / HIGH
    retry_count       INT          NOT NULL DEFAULT 0,
    failure_category  VARCHAR(32),
    created_at        TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_tool_call_message ON agent_tool_call (message_id);

-- -----------------------------------------------------------------------------
-- Spill record (metadata in DB; full payload in MinIO / S3-compatible store)
-- -----------------------------------------------------------------------------
CREATE TABLE agent_spill_record (
    spill_id            VARCHAR(64)  PRIMARY KEY,
    tool_call_id        VARCHAR(64)  NOT NULL REFERENCES agent_tool_call (tool_call_id),
    minio_object_key    VARCHAR(512) NOT NULL,
    original_size       INT          NOT NULL,
    preview             TEXT,
    expires_at          TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- Approval record (Human-in-the-Loop)
--   status: PENDING / APPROVED / REJECTED / EXPIRED
-- -----------------------------------------------------------------------------
CREATE TABLE agent_approval_record (
    approval_id    VARCHAR(64)  PRIMARY KEY,
    tool_call_id   VARCHAR(64)  NOT NULL REFERENCES agent_tool_call (tool_call_id),
    status         VARCHAR(32)  NOT NULL,
    decided_by     VARCHAR(128),
    reason         TEXT,
    decided_at     TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_approval_pending ON agent_approval_record (status) WHERE status = 'PENDING';

-- =============================================================================
-- Observability: independent schema, so it can be migrated wholesale to
-- ClickHouse / Elasticsearch later without touching business tables.
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS agent_runtime_obs;

CREATE TABLE agent_runtime_obs.span (
    span_id          VARCHAR(64)  PRIMARY KEY,
    parent_span_id   VARCHAR(64),
    session_id       VARCHAR(64)  NOT NULL REFERENCES agent_session (session_id),
    span_type        VARCHAR(32)  NOT NULL,           -- SESSION / TURN / MODEL_CALL / TOOL_CALL
    started_at       TIMESTAMP    NOT NULL,
    ended_at         TIMESTAMP
);
CREATE INDEX idx_agent_span_session ON agent_runtime_obs.span (session_id);
CREATE INDEX idx_agent_span_parent  ON agent_runtime_obs.span (parent_span_id);

CREATE TABLE agent_runtime_obs.event_record (
    event_id      VARCHAR(64)  PRIMARY KEY,
    span_id       VARCHAR(64)  NOT NULL REFERENCES agent_runtime_obs.span (span_id),
    event_type    VARCHAR(32)  NOT NULL,              -- THINKING / FINAL_REPLY / TOOL_INPUT / TOOL_OUTPUT / ERROR
    payload       JSONB,
    occurred_at   TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_event_span ON agent_runtime_obs.event_record (span_id);
