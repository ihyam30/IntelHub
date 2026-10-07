package com.intelhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CohereCompatibleRerankService implements RerankService {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public CohereCompatibleRerankService(@Value("${intelhub.ai.rerank.base-url:}") String baseUrl,
                                         @Value("${intelhub.ai.rerank.api-key:}") String apiKey,
                                         @Value("${intelhub.ai.rerank.model:}") String model,
                                         ObjectMapper mapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.mapper = mapper;
    }

    @Override public boolean configured() { return !baseUrl.isBlank() && !model.isBlank(); }
    @Override public String provider() { return "Cohere-compatible"; }
    @Override public String model() { return model.isBlank() ? "未配置" : model; }

    @Override
    public List<RankedDocument> rerank(String query, List<String> documents, int topN) throws Exception {
        if (!configured()) throw new IllegalStateException("未配置 Rerank 服务");
        if (documents.isEmpty()) return List.of();
        Map<String, Object> payload = Map.of("model", model, "query", query, "documents", documents,
                "top_n", Math.max(1, Math.min(topN, documents.size())));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Rerank provider returned HTTP " + response.statusCode());
        }
        JsonNode results = mapper.readTree(response.body()).path("results");
        if (!results.isArray()) throw new IllegalStateException("Rerank response has no results array");
        List<RankedDocument> ranked = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            if (index >= 0 && index < documents.size()) {
                ranked.add(new RankedDocument(index, result.path("relevance_score").asDouble(0)));
            }
        }
        if (ranked.isEmpty()) throw new IllegalStateException("Rerank response contained no valid document indexes");
        return ranked;
    }

    private String endpoint() {
        String trimmed = baseUrl.replaceAll("/+$", "");
        if (trimmed.endsWith("/rerank")) return trimmed;
        return trimmed + "/rerank";
    }
}
