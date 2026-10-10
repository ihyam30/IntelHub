UPDATE ingestion_pipeline AS pipeline
SET nodes = (
    SELECT jsonb_agg(
        CASE
            WHEN node ->> 'type' = 'chunk' THEN jsonb_set(
                node,
                '{settings}',
                jsonb_build_object('chunkSize', 512, 'overlapSize', 128),
                TRUE
            )
            ELSE node
        END
        ORDER BY position
    )
    FROM jsonb_array_elements(pipeline.nodes) WITH ORDINALITY AS steps(node, position)
)
WHERE pipeline.is_default = TRUE;
