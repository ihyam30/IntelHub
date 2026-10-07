UPDATE ingestion_pipeline AS pipeline
SET nodes = (
    SELECT jsonb_agg(ordered_steps.node ORDER BY ordered_steps.sort_order)
    FROM (
        SELECT step AS node, ordinality * 2 AS sort_order
        FROM jsonb_array_elements(pipeline.nodes) WITH ORDINALITY AS existing(step, ordinality)
        UNION ALL
        SELECT '{"type":"embedding"}'::jsonb, ordinality * 2 - 1
        FROM jsonb_array_elements(pipeline.nodes) WITH ORDINALITY AS existing(step, ordinality)
        WHERE step ->> 'type' = 'index'
    ) AS ordered_steps
)
WHERE jsonb_typeof(pipeline.nodes) = 'array'
  AND pipeline.nodes @> '[{"type":"index"}]'::jsonb
  AND NOT pipeline.nodes @> '[{"type":"embedding"}]'::jsonb;
