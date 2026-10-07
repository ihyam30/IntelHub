package com.intelhub.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionConsumerTest {
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> locks;
    private DocumentIngestionConsumer consumer;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        locks = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(locks);
        when(locks.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        consumer = new DocumentIngestionConsumer(jdbc, redis, mock(ObjectMapper.class), List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acknowledgesMessageWhenItsDocumentWasAlreadyDeleted() {
        UUID documentId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        consumer.onMessage(documentId.toString());

        verify(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        String lockKey = "intelhub:ingestion:lock:" + documentId;
        ArgumentCaptor<String> lockToken = ArgumentCaptor.forClass(String.class);
        verify(locks).setIfAbsent(eq(lockKey), lockToken.capture(), eq(Duration.ofMinutes(20)));
        verify(redis).execute(eq(DocumentIngestionConsumer.RELEASE_LOCK_SCRIPT), eq(List.of(lockKey)), eq(lockToken.getValue()));
        assertTrue(DocumentIngestionConsumer.RELEASE_LOCK_SCRIPT.getScriptAsString().contains("redis.call('get', KEYS[1]) == ARGV[1]"));
        assertTrue(DocumentIngestionConsumer.RELEASE_LOCK_SCRIPT.getScriptAsString().contains("redis.call('del', KEYS[1])"));
    }

    @Test
    void acknowledgesConcurrentDuplicateDeliveryWithoutTouchingTheDocument() {
        UUID documentId = UUID.randomUUID();
        String lockKey = "intelhub:ingestion:lock:" + documentId;
        when(locks.setIfAbsent(eq(lockKey), anyString(), eq(Duration.ofMinutes(20)))).thenReturn(false);

        consumer.onMessage(documentId.toString());

        org.mockito.Mockito.verifyNoInteractions(jdbc);
        verify(redis, never()).execute(eq(DocumentIngestionConsumer.RELEASE_LOCK_SCRIPT), eq(List.of(lockKey)), anyString());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void acknowledgesRedeliveryAfterTheDocumentWasAlreadyCompleted() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(documentId);
        when(row.getObject("knowledge_base_id", UUID.class)).thenReturn(knowledgeBaseId);
        when(row.getObject("owner_id", UUID.class)).thenReturn(ownerId);
        when(row.getObject("pipeline_id", UUID.class)).thenReturn(pipelineId);
        when(row.getString("filename")).thenReturn("already-done.txt");
        when(row.getString("object_key")).thenReturn("object-key");
        when(row.getString("status")).thenReturn("COMPLETED");
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        consumer.onMessage(documentId.toString());

        verify(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).queryForObject(anyString(), any(Class.class), any(Object[].class));
    }
}
