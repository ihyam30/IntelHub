ALTER TABLE ingestion_pipeline
    ALTER COLUMN nodes SET DEFAULT '[{"type":"parse"},{"type":"chunk"},{"type":"embedding"},{"type":"index"}]'::jsonb;
