CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(160) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_knowledge_base_owner ON knowledge_base(owner_id);

CREATE TABLE document_record (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    filename VARCHAR(500) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(200),
    file_size BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    error_message TEXT,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_kb_created ON document_record(knowledge_base_id, created_at DESC);

CREATE TABLE document_chunk (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document_record(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    page_number INTEGER,
    embedding vector(384) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(document_id, chunk_index)
);
CREATE INDEX idx_chunk_kb ON document_chunk(knowledge_base_id);
CREATE INDEX idx_chunk_owner ON document_chunk(owner_id);
CREATE INDEX idx_chunk_embedding ON document_chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE conversation (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    knowledge_base_id UUID REFERENCES knowledge_base(id),
    title VARCHAR(240) NOT NULL DEFAULT '新对话',
    summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_owner ON conversation(owner_id, updated_at DESC);

CREATE TABLE conversation_message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    model_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_message_conversation ON conversation_message(conversation_id, created_at);

CREATE TABLE rag_trace (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    conversation_id UUID REFERENCES conversation(id) ON DELETE SET NULL,
    query_text TEXT NOT NULL,
    retrieval JSONB NOT NULL DEFAULT '{}'::jsonb,
    model_id VARCHAR(100),
    status VARCHAR(24) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trace_owner_created ON rag_trace(owner_id, created_at DESC);

CREATE TABLE ingestion_pipeline (
    id UUID PRIMARY KEY,
    owner_id UUID REFERENCES app_user(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    nodes JSONB NOT NULL DEFAULT '[{"type":"parse"},{"type":"chunk"},{"type":"index"}]'::jsonb,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ingestion_run (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document_record(id) ON DELETE CASCADE,
    pipeline_id UUID REFERENCES ingestion_pipeline(id) ON DELETE SET NULL,
    status VARCHAR(24) NOT NULL,
    current_node VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_ingestion_run_document ON ingestion_run(document_id, started_at DESC);

INSERT INTO ingestion_pipeline(id, name, nodes, is_default)
VALUES ('00000000-0000-0000-0000-000000000001', '默认知识入库流程',
        '[{"type":"parse"},{"type":"normalize"},{"type":"chunk"},{"type":"metadata"},{"type":"index"}]'::jsonb, TRUE);

ALTER TABLE document_record ADD COLUMN pipeline_id UUID REFERENCES ingestion_pipeline(id) ON DELETE SET NULL;
ALTER TABLE document_record ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE TABLE ingestion_node_run (
    id UUID PRIMARY KEY,
    ingestion_run_id UUID NOT NULL REFERENCES ingestion_run(id) ON DELETE CASCADE,
    node_type VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_node_run_parent ON ingestion_node_run(ingestion_run_id, started_at);

CREATE TABLE intent_node (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    keywords JSONB NOT NULL DEFAULT '[]'::jsonb,
    prompt_hint TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO intent_node(id,name,description,keywords,prompt_hint) VALUES
('10000000-0000-0000-0000-000000000001','IT 支持','账号、网络、软件和系统问题','["登录","账号","网络","系统","软件","报错","权限","无法访问"]'::jsonb,'优先关注故障现象、影响范围和处理步骤。'),
('10000000-0000-0000-0000-000000000002','人事制度','招聘、培训、薪酬与员工制度','["招聘","培训","薪酬","工资","福利","员工","请假","制度"]'::jsonb,'准确说明适用范围、条件和流程。'),
('10000000-0000-0000-0000-000000000003','财务流程','报销、发票、付款和预算','["报销","发票","开票","付款","预算","财务","税务"]'::jsonb,'优先提取金额、材料和审批流程。');
