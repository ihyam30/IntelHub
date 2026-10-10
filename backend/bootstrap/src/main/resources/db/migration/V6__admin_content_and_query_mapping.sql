ALTER TABLE intent_node
    ADD COLUMN parent_id UUID REFERENCES intent_node(id) ON DELETE CASCADE;

CREATE INDEX idx_intent_node_parent ON intent_node(parent_id, name);

CREATE TABLE query_term_mapping (
    id UUID PRIMARY KEY,
    source_term VARCHAR(120) NOT NULL,
    target_terms JSONB NOT NULL DEFAULT '[]'::jsonb,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_query_term_mapping_source ON query_term_mapping(lower(source_term));

CREATE TABLE sample_question (
    id UUID PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    question VARCHAR(1000) NOT NULL,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO sample_question(id,title,question,description,sort_order) VALUES
('20000000-0000-0000-0000-000000000001','内容总结','请总结当前知识库中的主要内容，并列出对应来源。','提炼资料中的关键主题和结论',1),
('20000000-0000-0000-0000-000000000002','查找重点','知识库中有哪些需要特别注意的规则或限制？','定位制度、要求和适用条件',2),
('20000000-0000-0000-0000-000000000003','对比信息','请比较文档中的关键数据和时间节点。','对照资料中的数据、版本和时间',3);
