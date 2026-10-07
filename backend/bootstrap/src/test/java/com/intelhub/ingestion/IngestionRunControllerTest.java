package com.intelhub.ingestion;

import com.intelhub.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionRunControllerTest {
    @Test
    void manualRetryRequeuesOnlyFailedOwnedDocuments() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        DocumentIngestionProducer producer = mock(DocumentIngestionProducer.class);
        UUID ownerId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(ownerId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        new IngestionRunController(jdbc, currentUser, producer).retry(documentId);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status='QUEUED'"), eq(documentId), eq(ownerId));
        verify(producer).enqueue(documentId);
    }
}
