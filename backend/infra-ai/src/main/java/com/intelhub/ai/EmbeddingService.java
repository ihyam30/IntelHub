package com.intelhub.ai;

public interface EmbeddingService {
    float[] embed(String text);
    int dimension();
    boolean remoteConfigured();
    String provider();
    String model();
}
