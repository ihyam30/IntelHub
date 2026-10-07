package com.intelhub.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RocketMQMessageListener(topic = "${intelhub.ingestion.topic}", consumerGroup = "intelhub-document-consumer")
public class DocumentIngestionConsumer implements RocketMQListener<String> {
    static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Map<String, IngestionNode> nodes;

    public DocumentIngestionConsumer(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper mapper, List<IngestionNode> nodeList) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.mapper = mapper;
        this.nodes = nodeList.stream().collect(java.util.stream.Collectors.toMap(IngestionNode::type, node -> node));
    }

    @Override
    public void onMessage(String message) {
        UUID documentId = UUID.fromString(message.trim());
        String lockKey = "intelhub:ingestion:lock:" + documentId;
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofMinutes(20));
        if (!Boolean.TRUE.equals(locked)) return;
        UUID runId = UUID.randomUUID();
        try {
            List<DocumentRow> documents = jdbc.query("SELECT id,knowledge_base_id,owner_id,filename,object_key,pipeline_id,status FROM document_record WHERE id=?",
                    (rs, n) -> new DocumentRow(rs.getObject("id", UUID.class), rs.getObject("knowledge_base_id", UUID.class), rs.getObject("owner_id", UUID.class),
                            rs.getString("filename"), rs.getString("object_key"), rs.getObject("pipeline_id", UUID.class), rs.getString("status")), documentId);
            // A document may have been deleted after its RocketMQ message was published.
            // Acknowledge that stale message instead of retrying it forever.
            if (documents.isEmpty()) return;
            DocumentRow document = documents.get(0);
            if ("COMPLETED".equals(document.status())) return;
            jdbc.update("UPDATE document_record SET status='PROCESSING',error_message=NULL,updated_at=now() WHERE id=?", documentId);
            jdbc.update("INSERT INTO ingestion_run(id,document_id,pipeline_id,status,current_node) VALUES (?,?,?,'PROCESSING',NULL)", runId, documentId, document.pipelineId());
            List<String> steps = loadPipeline(document);
            IngestionContext context = new IngestionContext(runId, documentId, document.knowledgeBaseId(), document.ownerId(), document.filename(), document.objectKey());
            for (String type : steps) executeNode(context, type);
            jdbc.update("UPDATE document_record SET status='COMPLETED',chunk_count=?,updated_at=now() WHERE id=?", context.chunks().size(), documentId);
            jdbc.update("UPDATE ingestion_run SET status='COMPLETED',current_node=NULL,finished_at=now() WHERE id=?", runId);
        } catch (Exception exception) {
            String messageText = exception.getMessage() == null ? "文档处理失败" : exception.getMessage();
            jdbc.update("UPDATE document_record SET status='FAILED',error_message=?,updated_at=now() WHERE id=?", truncate(messageText), documentId);
            jdbc.update("UPDATE ingestion_run SET status='FAILED',error_message=?,finished_at=now() WHERE id=?", truncate(messageText), runId);
            throw new IllegalStateException("Ingestion failed for " + documentId, exception);
        } finally {
            // Compare and delete atomically so an expired lock cannot be replaced between GET and DEL.
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockToken);
        }
    }

    private List<String> loadPipeline(DocumentRow document) throws Exception {
        UUID pipelineId = document.pipelineId();
        if (pipelineId == null) {
            pipelineId = jdbc.queryForObject("SELECT id FROM ingestion_pipeline WHERE is_default=TRUE ORDER BY created_at LIMIT 1", UUID.class);
        }
        String json = jdbc.queryForObject("SELECT nodes FROM ingestion_pipeline WHERE id=? AND (owner_id=? OR owner_id IS NULL)", String.class, pipelineId, document.ownerId());
        List<Map<String, String>> stepMaps = mapper.readValue(json, new TypeReference<>() {});
        return stepMaps.stream().map(step -> step.get("type")).toList();
    }

    private void executeNode(IngestionContext context, String type) throws Exception {
        IngestionNode node = nodes.get(type);
        if (node == null) throw new IllegalArgumentException("未知入库节点: " + type);
        long start = System.currentTimeMillis();
        UUID nodeRun = UUID.randomUUID();
        jdbc.update("UPDATE ingestion_run SET current_node=? WHERE id=?", type, context.runId());
        jdbc.update("INSERT INTO ingestion_node_run(id,ingestion_run_id,node_type,status) VALUES (?,?,?,'PROCESSING')", nodeRun, context.runId(), type);
        try {
            node.execute(context);
            jdbc.update("UPDATE ingestion_node_run SET status='COMPLETED',duration_ms=?,finished_at=now() WHERE id=?", System.currentTimeMillis() - start, nodeRun);
        } catch (Exception exception) {
            jdbc.update("UPDATE ingestion_node_run SET status='FAILED',duration_ms=?,error_message=?,finished_at=now() WHERE id=?", System.currentTimeMillis() - start, truncate(exception.getMessage()), nodeRun);
            throw exception;
        }
    }

    private static String truncate(String value) {
        if (value == null) return "处理失败";
        return value.length() > 1800 ? value.substring(0, 1800) : value;
    }

    private record DocumentRow(UUID id, UUID knowledgeBaseId, UUID ownerId, String filename, String objectKey, UUID pipelineId, String status) {}
}
