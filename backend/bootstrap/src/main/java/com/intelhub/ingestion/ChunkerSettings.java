package com.intelhub.ingestion;

/**
 * Character-based settings for the ingestion chunk node.
 */
public record ChunkerSettings(Integer chunkSize, Integer overlapSize) {
    public static final int DEFAULT_CHUNK_SIZE = 512;
    public static final int DEFAULT_OVERLAP_SIZE = 128;
    public static final int MAX_CHUNK_SIZE = 10_000;

    public static ChunkerSettings defaults() {
        return new ChunkerSettings(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP_SIZE);
    }

    public static int maxOverlapSize(int chunkSize) {
        return Math.max(0, chunkSize / 2);
    }

    public ChunkerSettings withDefaults() {
        return new ChunkerSettings(
                chunkSize == null ? DEFAULT_CHUNK_SIZE : chunkSize,
                overlapSize == null ? DEFAULT_OVERLAP_SIZE : overlapSize);
    }
}
