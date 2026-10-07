package com.intelhub.ingestion;

public interface IngestionNode {
    String type();
    void execute(IngestionContext context) throws Exception;
}
