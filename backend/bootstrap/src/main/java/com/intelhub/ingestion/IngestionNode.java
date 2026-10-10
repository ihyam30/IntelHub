package com.intelhub.ingestion;

public interface IngestionNode {
    String type();
    void execute(IngestionContext context) throws Exception;

    default void execute(IngestionContext context, ChunkerSettings settings) throws Exception {
        execute(context);
    }
}
