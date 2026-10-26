-- 轮次执行：消息带上 turn_id，便于整轮重跑前删除；轮次终态镜像到 agent_turn。
-- 租约和回放事件仍在 Redis。

ALTER TABLE agent_message ADD COLUMN turn_id VARCHAR(64);
CREATE INDEX idx_agent_message_session_turn ON agent_message (session_id, turn_id);

CREATE TABLE agent_turn (
    turn_id       VARCHAR(64)  PRIMARY KEY,
    session_id    VARCHAR(64)  NOT NULL REFERENCES agent_session (session_id),
    user_id       VARCHAR(128),
    agent_name    VARCHAR(128) NOT NULL,
    query         TEXT         NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    error_code    VARCHAR(64),
    final_answer  TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_turn_recover ON agent_turn (agent_name, status);
