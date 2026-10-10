package com.intelhub.ingestion;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkerNodeTest {
    private final ChunkerNode chunker = new ChunkerNode();

    @Test
    void rejectsOverlapAlmostAsLargeAsChunkSize() {
        IngestionContext context = context("x".repeat(10_000));

        assertThatThrownBy(() -> chunker.execute(context, new ChunkerSettings(512, 511)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50%");
        assertThat(context.chunks()).isEmpty();
    }

    @Test
    void maximumAllowedOverlapKeepsLongUnbrokenTextToBoundedChunkCount() {
        IngestionContext context = context("x".repeat(10_000));

        chunker.execute(context, new ChunkerSettings(512, 256));

        assertThat(context.chunks()).hasSize(39);
        assertThat(context.chunks()).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(512));
    }

    private IngestionContext context(String text) {
        IngestionContext context = new IngestionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "fixture.txt", "fixture.txt");
        context.text(text);
        return context;
    }
}
