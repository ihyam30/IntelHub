UPDATE ingestion_pipeline AS pipeline
SET nodes = (
    SELECT jsonb_agg(
        CASE
            WHEN node ->> 'type' = 'chunk' THEN jsonb_set(
                node,
                '{settings}',
                jsonb_set(
                    COALESCE(NULLIF(node -> 'settings', 'null'::jsonb), '{}'::jsonb),
                    '{overlapSize}',
                    to_jsonb(LEAST(
                        COALESCE(NULLIF(node #>> '{settings,overlapSize}', '')::integer, 128),
                        GREATEST(0, COALESCE(NULLIF(node #>> '{settings,chunkSize}', '')::integer, 512) / 2)
                    )),
                    TRUE
                ),
                TRUE
            )
            ELSE node
        END
        ORDER BY position
    )
    FROM jsonb_array_elements(pipeline.nodes) WITH ORDINALITY AS steps(node, position)
)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(pipeline.nodes) AS steps(node)
    WHERE node ->> 'type' = 'chunk'
      AND COALESCE(NULLIF(node #>> '{settings,overlapSize}', '')::integer, 128)
          > GREATEST(0, COALESCE(NULLIF(node #>> '{settings,chunkSize}', '')::integer, 512) / 2)
);
