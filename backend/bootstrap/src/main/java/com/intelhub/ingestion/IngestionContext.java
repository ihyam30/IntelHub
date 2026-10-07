package com.intelhub.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

public class IngestionContext {
    private final UUID runId;
    private final UUID documentId;
    private final UUID knowledgeBaseId;
    private final UUID ownerId;
    private final String filename;
    private final String objectKey;
    private String text;
    private List<String> chunks = new ArrayList<>();
    private List<float[]> embeddings = new ArrayList<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    public IngestionContext(UUID runId, UUID documentId, UUID knowledgeBaseId, UUID ownerId, String filename, String objectKey) {
        this.runId = runId;
        this.documentId = documentId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.ownerId = ownerId;
        this.filename = filename;
        this.objectKey = objectKey;
    }

    public UUID runId() { return runId; }
    public UUID documentId() { return documentId; }
    public UUID knowledgeBaseId() { return knowledgeBaseId; }
    public UUID ownerId() { return ownerId; }
    public String filename() { return filename; }
    public String objectKey() { return objectKey; }
    public String text() { return text; }
    public void text(String text) { this.text = text; }
    public List<String> chunks() { return chunks; }
    public void chunks(List<String> chunks) { this.chunks = chunks; }
    public List<float[]> embeddings() { return embeddings; }
    public void embeddings(List<float[]> embeddings) { this.embeddings = embeddings; }
    public Map<String, Object> metadata() { return metadata; }
}
