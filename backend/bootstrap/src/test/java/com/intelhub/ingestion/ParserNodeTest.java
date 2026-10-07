package com.intelhub.ingestion;

import com.intelhub.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParserNodeTest {
    @Test
    void parsesGenuineWord97BinaryDocument() throws Exception {
        byte[] content;
        try (InputStream fixture = getClass().getResourceAsStream("/fixtures/apache-poi-simple.doc")) {
            assertThat(fixture).as("legacy Word fixture").isNotNull();
            content = fixture.readAllBytes();
        }
        assertThat(Arrays.copyOf(content, 8)).containsExactly(
                (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1);

        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.get("fixture/simple.doc")).thenReturn(content);
        IngestionContext context = new IngestionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "simple.doc", "fixture/simple.doc");

        new ParserNode(storage).execute(context);

        assertThat(context.text()).isNotBlank().hasSizeGreaterThan(20).doesNotContain("{\\rtf1");
    }
}
