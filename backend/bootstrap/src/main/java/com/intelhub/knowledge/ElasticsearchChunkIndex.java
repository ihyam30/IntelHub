package com.intelhub.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ElasticsearchChunkIndex {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String index;

    public ElasticsearchChunkIndex(@Value("${intelhub.elasticsearch.endpoint}") String endpoint,
                                   @Value("${intelhub.elasticsearch.index}") String index,
                                   ObjectMapper mapper) {
        this.client = RestClient.builder(HttpHost.create(endpoint)).build();
        this.mapper = mapper;
        this.index = index;
    }

    @PostConstruct
    void createIndex() {
        try {
            createIndexWithAnalyzer("ik_max_word", "ik_smart");
        } catch (Exception exception) {
            try {
                client.performRequest(new Request("HEAD", "/" + index));
                return; // An existing index is retained.
            } catch (Exception missing) {
                try { createIndexWithAnalyzer("standard", "standard"); }
                catch (Exception unavailable) { /* Requests continue with vector-only fallback until ES recovers. */ }
            }
        }
    }

    private void createIndexWithAnalyzer(String analyzer, String searchAnalyzer) throws IOException {
        Request request = new Request("PUT", "/" + index);
        request.setJsonEntity(mapper.writeValueAsString(Map.of("mappings", Map.of("properties", Map.of(
                "chunk_id", Map.of("type", "keyword"), "document_id", Map.of("type", "keyword"),
                "knowledge_base_id", Map.of("type", "keyword"), "owner_id", Map.of("type", "keyword"),
                "filename", Map.of("type", "text", "analyzer", analyzer, "search_analyzer", searchAnalyzer),
                "content", Map.of("type", "text", "analyzer", analyzer, "search_analyzer", searchAnalyzer))))));
        client.performRequest(request);
    }

    public void index(UUID chunkId, UUID documentId, UUID knowledgeBaseId, UUID ownerId, String filename, String content) throws IOException {
        Request request = new Request("PUT", "/" + index + "/_doc/" + chunkId);
        request.setJsonEntity(mapper.writeValueAsString(Map.of("chunk_id", chunkId.toString(), "document_id", documentId.toString(),
                "knowledge_base_id", knowledgeBaseId.toString(), "owner_id", ownerId.toString(), "filename", filename, "content", content)));
        client.performRequest(request);
    }

    public List<KeywordHit> search(UUID knowledgeBaseId, UUID ownerId, String query, boolean admin, int limit) throws IOException {
        Map<String, Object> ownerFilter = admin ? Map.of("match_all", Map.of()) : Map.of("term", Map.of("owner_id", ownerId.toString()));
        Request request = new Request("POST", "/" + index + "/_search");
        request.setJsonEntity(mapper.writeValueAsString(Map.of("size", limit, "query", Map.of("bool", Map.of(
                "must", List.of(Map.of("match", Map.of("content", Map.of("query", query, "operator", "or")))),
                "filter", List.of(Map.of("term", Map.of("knowledge_base_id", knowledgeBaseId.toString())), ownerFilter))),
                "_source", List.of("chunk_id", "document_id", "filename", "content"))));
        Response response = client.performRequest(request);
        JsonNode root = mapper.readTree(response.getEntity().getContent());
        List<KeywordHit> hits = new ArrayList<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            hits.add(new KeywordHit(source.path("chunk_id").asText(), source.path("document_id").asText(),
                    source.path("filename").asText(), source.path("content").asText(), hit.path("_score").asDouble()));
        }
        return hits;
    }

    public void deleteDocument(UUID documentId) throws IOException {
        Request request = new Request("POST", "/" + index + "/_delete_by_query?conflicts=proceed&refresh=true");
        request.setJsonEntity(mapper.writeValueAsString(Map.of("query", Map.of("term", Map.of("document_id", documentId.toString())))));
        performDeleteByQuery(request);
    }

    public void deleteKnowledgeBase(UUID knowledgeBaseId) throws IOException {
        Request request = new Request("POST", "/" + index + "/_delete_by_query?conflicts=proceed&refresh=true");
        request.setJsonEntity(mapper.writeValueAsString(Map.of("query", Map.of("term", Map.of("knowledge_base_id", knowledgeBaseId.toString())))));
        performDeleteByQuery(request);
    }

    private void performDeleteByQuery(Request request) throws IOException {
        try {
            client.performRequest(request);
        } catch (ResponseException exception) {
            if (exception.getResponse().getStatusLine().getStatusCode() != 404) throw exception;
            // A missing index means there are no keyword records to clean up.
        }
    }

    @PreDestroy
    void close() throws IOException { client.close(); }

    public record KeywordHit(String chunkId, String documentId, String filename, String content, double score) {}
}
