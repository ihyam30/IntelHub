package com.intelhub.ai;

import java.util.List;

public interface RerankService {
    boolean configured();
    String provider();
    String model();
    List<RankedDocument> rerank(String query, List<String> documents, int topN) throws Exception;

    record RankedDocument(int index, double score) {}
}
