ALTER TABLE conversation
    ADD COLUMN summary_message_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN pending_question TEXT,
    ADD COLUMN pending_intents JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_conversation_pending_owner
    ON conversation(owner_id, updated_at DESC)
    WHERE pending_question IS NOT NULL;
