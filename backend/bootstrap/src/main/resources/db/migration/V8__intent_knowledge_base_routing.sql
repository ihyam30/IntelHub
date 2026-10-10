CREATE TABLE intent_knowledge_base (
    intent_id UUID NOT NULL REFERENCES intent_node(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (intent_id, knowledge_base_id)
);

CREATE INDEX idx_intent_knowledge_base_knowledge_base_id
    ON intent_knowledge_base(knowledge_base_id);
