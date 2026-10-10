CREATE TABLE message_feedback (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES conversation_message(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    vote SMALLINT NOT NULL CHECK (vote IN (-1, 1)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, user_id)
);

CREATE INDEX idx_message_feedback_user_updated ON message_feedback(user_id, updated_at DESC);
