package com.intelhub.knowledge;

import com.intelhub.common.CurrentUser;
import com.intelhub.ingestion.DocumentIngestionProducer;
import com.intelhub.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentControllerTest {
    private JdbcTemplate jdbc;
    private CurrentUser currentUser;
    private ObjectStorageService storage;
    private DocumentIngestionProducer producer;
    private ElasticsearchChunkIndex keywordIndex;
    private DocumentController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        currentUser = mock(CurrentUser.class);
        storage = mock(ObjectStorageService.class);
        producer = mock(DocumentIngestionProducer.class);
        keywordIndex = mock(ElasticsearchChunkIndex.class);
        controller = new DocumentController(jdbc, currentUser, storage, producer, keywordIndex);
    }

    @Test
    void deleteCleansSearchIndexAndMinioBeforeRemovingOwnedDocument() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        String key = "owner/kb/document/file.txt";
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("object_key", key)));

        controller.delete(documentId);

        var order = inOrder(keywordIndex, storage, jdbc);
        order.verify(keywordIndex).deleteDocument(documentId);
        order.verify(storage).delete(key);
        order.verify(jdbc).update(contains("DELETE FROM document_record WHERE id=? AND owner_id=?"),
                org.mockito.ArgumentMatchers.eq(documentId), org.mockito.ArgumentMatchers.eq(userId));
    }

    @Test
    void deleteDoesNotRemoveDatabaseRecordWhenMinioCleanupFails() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("object_key", "owner/kb/file.txt")));
        doThrow(new IllegalStateException("MinIO unavailable")).when(storage).delete("owner/kb/file.txt");

        assertThatThrownBy(() -> controller.delete(documentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MinIO unavailable");
        verify(keywordIndex).deleteDocument(documentId);
        verify(jdbc, never()).update(contains("DELETE FROM document_record"), any(), any());
    }

    @Test
    void deleteHidesDocumentsOwnedByAnotherUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> controller.delete(documentId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode().value()).isEqualTo(404));
        verify(keywordIndex, never()).deleteDocument(any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void enqueueFailureOnlyMarksDocumentFailedWhileItIsStillQueued() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        byte[] bytes = "test document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MultipartFile file = mock(MultipartFile.class);
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("test.txt");
        when(file.getContentType()).thenReturn("text/plain");
        when(file.getSize()).thenReturn((long) bytes.length);
        when(file.getBytes()).thenReturn(bytes);
        doThrow(new IllegalStateException("broker response timeout")).when(producer).enqueue(any(UUID.class));

        assertThatThrownBy(() -> controller.upload(knowledgeBaseId, file, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker response timeout");

        var documentId = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(producer).enqueue(documentId.capture());
        var failedUpdate = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(failedUpdate.capture(), eq(documentId.getValue()));
        org.assertj.core.api.Assertions.assertThat(failedUpdate.getValue())
                .contains("status='FAILED'", "AND status='QUEUED'");
    }
}
