package com.intelhub.knowledge;

import com.intelhub.common.CurrentUser;
import com.intelhub.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeControllerTest {
    private JdbcTemplate jdbc;
    private CurrentUser currentUser;
    private ObjectStorageService storage;
    private ElasticsearchChunkIndex keywordIndex;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        currentUser = mock(CurrentUser.class);
        storage = mock(ObjectStorageService.class);
        keywordIndex = mock(ElasticsearchChunkIndex.class);
        controller = new KnowledgeController(jdbc, currentUser, storage, keywordIndex);
    }

    @Test
    void updateOnlyChangesKnowledgeBaseOwnedByCurrentUser() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        var result = controller.update(knowledgeBaseId,
                new KnowledgeController.UpdateKnowledgeBaseRequest("  Updated  ", "  Description  "));

        assertThat(result.success()).isTrue();
        verify(jdbc).update(contains("UPDATE knowledge_base SET name=?,description=? WHERE id=? AND owner_id=?"),
                eq("Updated"), eq("Description"), eq(knowledgeBaseId), eq(userId));
    }

    @Test
    void updateHidesKnowledgeBasesOwnedByAnotherUser() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> controller.update(knowledgeBaseId,
                new KnowledgeController.UpdateKnowledgeBaseRequest("Updated", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verify(jdbc).update(contains("owner_id=?"), eq("Updated"), isNull(), eq(knowledgeBaseId), eq(userId));
        verify(storage, never()).deletePrefix(anyString());
    }

    @Test
    void deleteCleansKeywordIndexAndObjectPrefixBeforeCascadingDatabaseRows() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "object_key", "file.txt")));
        when(jdbc.update(anyString(), any(UUID.class), any(UUID.class))).thenReturn(1);

        var result = controller.delete(knowledgeBaseId);

        assertThat(result.success()).isTrue();
        var order = inOrder(keywordIndex, storage, jdbc);
        order.verify(keywordIndex).deleteKnowledgeBase(knowledgeBaseId);
        order.verify(storage).deletePrefix(userId + "/" + knowledgeBaseId + "/");
        order.verify(jdbc).update(contains("DELETE FROM knowledge_base WHERE id=? AND owner_id=?"), eq(knowledgeBaseId), eq(userId));
    }

    @Test
    void deleteLeavesDatabaseRowsWhenMinioCleanupFailsSoTheOperationCanBeRetried() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        doThrow(new IllegalStateException("MinIO unavailable"))
                .when(storage).deletePrefix(userId + "/" + knowledgeBaseId + "/");

        assertThatThrownBy(() -> controller.delete(knowledgeBaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MinIO unavailable");
        verify(jdbc, never()).update(contains("DELETE FROM knowledge_base"), any(), any());
    }

    @Test
    void deleteDoesNotCleanResourcesWhenUserDoesNotOwnKnowledgeBase() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> controller.delete(knowledgeBaseId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verify(keywordIndex, never()).deleteKnowledgeBase(any());
        verify(storage, never()).deletePrefix(anyString());
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }
}
