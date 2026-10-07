package com.intelhub.ingestion;

import com.intelhub.ai.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingNodeTest {
    @Test
    void createsOneVectorForEveryChunk() {
        EmbeddingService service = mock(EmbeddingService.class);
        when(service.dimension()).thenReturn(2);
        when(service.embed("first")).thenReturn(new float[]{0.1f, 0.2f});
        when(service.embed("second")).thenReturn(new float[]{0.3f, 0.4f});
        IngestionContext context = new IngestionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "notes.txt", "object-key");
        context.chunks(List.of("first", "second"));

        new EmbeddingNode(service).execute(context);

        assertThat(context.embeddings()).containsExactly(new float[]{0.1f, 0.2f}, new float[]{0.3f, 0.4f});
        verify(service).embed("first");
        verify(service).embed("second");
    }
}
