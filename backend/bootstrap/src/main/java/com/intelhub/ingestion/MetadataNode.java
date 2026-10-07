package com.intelhub.ingestion;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class MetadataNode implements IngestionNode {
    @Override public String type() { return "metadata"; }
    @Override public void execute(IngestionContext context) {
        context.metadata().put("filename", context.filename());
        context.metadata().put("sourceObject", context.objectKey());
        context.metadata().put("processedAt", OffsetDateTime.now().toString());
        context.metadata().put("characterCount", context.text() == null ? 0 : context.text().length());
    }
}
