package com.intelhub.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.ingestion.IngestionContext;
import com.intelhub.ingestion.IndexerNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexerNodeTest {
    @Test
    void writesPrecomputedEmbeddingsToVectorAndKeywordIndexes() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ElasticsearchChunkIndex keywordIndex = mock(ElasticsearchChunkIndex.class);
        UUID documentId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        IngestionContext context = new IngestionContext(UUID.randomUUID(), documentId, knowledgeBaseId,
                ownerId, "notes.txt", "object-key");
        context.chunks(List.of("retrievable text"));
        context.embeddings(List.of(new float[]{0.25f, -0.5f}));

        new IndexerNode(jdbc, keywordIndex, new ObjectMapper()).execute(context);

        var insertArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO document_chunk"), insertArgs.capture());
        assertThat(insertArgs.getValue()[6]).isEqualTo("[0.25, -0.5]");
        var indexedChunkId = (UUID) insertArgs.getValue()[0];
        verify(keywordIndex).index(indexedChunkId, documentId, knowledgeBaseId, ownerId, "notes.txt", "retrievable text");
    }

    @Test
    void validatesVectorsBeforeReplacingExistingIndexes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ElasticsearchChunkIndex keywordIndex = mock(ElasticsearchChunkIndex.class);
        IngestionContext context = new IngestionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "notes.txt", "object-key");
        context.chunks(List.of("retrievable text"));

        assertThatThrownBy(() -> new IndexerNode(jdbc, keywordIndex, new ObjectMapper()).execute(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Embedding 节点");
        org.mockito.Mockito.verifyNoInteractions(jdbc, keywordIndex);
    }

    @Test
    void retryAfterKeywordIndexFailureReplacesPartialVectorAndKeywordIndexesDeterministically() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ElasticsearchChunkIndex keywordIndex = mock(ElasticsearchChunkIndex.class);
        UUID documentId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        IngestionContext context = new IngestionContext(UUID.randomUUID(), documentId, knowledgeBaseId,
                ownerId, "policy.txt", "object-key");
        context.chunks(List.of("first retrievable passage", "second retrievable passage"));
        context.embeddings(List.of(new float[]{0.1f, 0.2f}, new float[]{0.3f, 0.4f}));
        doThrow(new IOException("elasticsearch temporarily unavailable"))
                .doNothing()
                .when(keywordIndex).index(any(UUID.class), eq(documentId), eq(knowledgeBaseId), eq(ownerId), eq("policy.txt"), any());
        IndexerNode indexer = new IndexerNode(jdbc, keywordIndex, new ObjectMapper());

        assertThatThrownBy(() -> indexer.execute(context))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("temporarily unavailable");
        indexer.execute(context);

        var insertedIds = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).update(contains("INSERT INTO document_chunk"), insertedIds.capture());
        UUID firstAttemptId = (UUID) insertedIds.getAllValues().get(0)[0];
        UUID retryFirstId = (UUID) insertedIds.getAllValues().get(1)[0];
        UUID retrySecondId = (UUID) insertedIds.getAllValues().get(2)[0];
        assertThat(firstAttemptId).isEqualTo(retryFirstId);
        assertThat(retrySecondId).isNotEqualTo(retryFirstId);
        verify(jdbc, times(2)).update(contains("DELETE FROM document_chunk"), eq(documentId));
        verify(keywordIndex, times(2)).deleteDocument(documentId);
        verify(keywordIndex, times(3)).index(any(UUID.class), eq(documentId), eq(knowledgeBaseId), eq(ownerId), eq("policy.txt"), any());
    }
}
