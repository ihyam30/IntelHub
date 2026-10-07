package com.intelhub.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionConsumerFailureTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordsFailedNodeAndRunThenRethrowsForRocketMqRedelivery() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> locks = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(locks);
        when(locks.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        UUID documentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(documentId);
        when(row.getObject("knowledge_base_id", UUID.class)).thenReturn(knowledgeBaseId);
        when(row.getObject("owner_id", UUID.class)).thenReturn(ownerId);
        when(row.getObject("pipeline_id", UUID.class)).thenReturn(pipelineId);
        when(row.getString("filename")).thenReturn("failure.txt");
        when(row.getString("object_key")).thenReturn("object-key");
        when(row.getString("status")).thenReturn("QUEUED");
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(contains("SELECT id FROM ingestion_pipeline"), eq(UUID.class))).thenReturn(pipelineId);
        when(jdbc.queryForObject(contains("SELECT nodes FROM ingestion_pipeline"), eq(String.class), eq(pipelineId), eq(ownerId)))
                .thenReturn("[{\"type\":\"fail\"}]");

        IngestionNode failingNode = new IngestionNode() {
            @Override public String type() { return "fail"; }
            @Override public void execute(IngestionContext context) { throw new IllegalStateException("controlled node failure"); }
        };
        DocumentIngestionConsumer consumer = new DocumentIngestionConsumer(jdbc, redis, new ObjectMapper(), List.of(failingNode));

        assertThatThrownBy(() -> consumer.onMessage(documentId.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ingestion failed")
                .hasRootCauseMessage("controlled node failure");

        verify(jdbc).update(contains("UPDATE ingestion_node_run SET status='FAILED'"), any(Object[].class));
        verify(jdbc).update(contains("UPDATE ingestion_run SET status='FAILED'"), any(Object[].class));
        verify(jdbc).update(contains("UPDATE document_record SET status='FAILED'"), any(Object[].class));
        String lockKey = "intelhub:ingestion:lock:" + documentId;
        ArgumentCaptor<String> lockToken = ArgumentCaptor.forClass(String.class);
        verify(locks).setIfAbsent(eq(lockKey), lockToken.capture(), eq(Duration.ofMinutes(20)));
        verify(redis).execute(eq(DocumentIngestionConsumer.RELEASE_LOCK_SCRIPT), eq(List.of(lockKey)), eq(lockToken.getValue()));
    }
}
