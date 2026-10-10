package com.intelhub.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.common.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionPipelineControllerTest {
    private JdbcTemplate jdbc;
    private CurrentUser currentUser;
    private IngestionPipelineController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(UUID.randomUUID());
        controller = new IngestionPipelineController(jdbc, currentUser, new ObjectMapper());
    }

    @Test
    void createsAComposablePipelineWithSeparateEmbeddingStep() throws Exception {
        var request = new IngestionPipelineController.PipelineRequest("Minimal pipeline", List.of(
                new IngestionPipelineController.NodeStep("parse"),
                new IngestionPipelineController.NodeStep("chunk"),
                new IngestionPipelineController.NodeStep("embedding"),
                new IngestionPipelineController.NodeStep("index")));

        var result = controller.create(request);

        assertThat(result.success()).isTrue();
        assertThat(result.data().nodes()).extracting(IngestionPipelineController.NodeStep::type)
                .containsExactly("parse", "chunk", "embedding", "index");
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsPipelineWithoutEmbeddingStep() {
        var request = new IngestionPipelineController.PipelineRequest("Missing embedding", List.of(
                new IngestionPipelineController.NodeStep("parse"),
                new IngestionPipelineController.NodeStep("chunk"),
                new IngestionPipelineController.NodeStep("index")));

        assertThatThrownBy(() -> controller.create(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsEmbeddingBeforeChunking() {
        var request = new IngestionPipelineController.PipelineRequest("Wrong order", List.of(
                new IngestionPipelineController.NodeStep("parse"),
                new IngestionPipelineController.NodeStep("embedding"),
                new IngestionPipelineController.NodeStep("chunk"),
                new IngestionPipelineController.NodeStep("index")));

        assertThatThrownBy(() -> controller.create(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsNormalizationAfterChunking() {
        var request = new IngestionPipelineController.PipelineRequest("Wrong normalization order", List.of(
                new IngestionPipelineController.NodeStep("parse"),
                new IngestionPipelineController.NodeStep("chunk"),
                new IngestionPipelineController.NodeStep("normalize"),
                new IngestionPipelineController.NodeStep("embedding"),
                new IngestionPipelineController.NodeStep("index")));

        assertThatThrownBy(() -> controller.create(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsPipelineWithOverlapAboveHalfTheChunkSize() {
        var request = new IngestionPipelineController.PipelineRequest("Excessive overlap", List.of(
                new IngestionPipelineController.NodeStep("parse"),
                new IngestionPipelineController.NodeStep("chunk", new ChunkerSettings(512, 511)),
                new IngestionPipelineController.NodeStep("embedding"),
                new IngestionPipelineController.NodeStep("index")));

        assertThatThrownBy(() -> controller.create(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
