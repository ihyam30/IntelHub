package com.intelhub.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Stable local embeddings keep the demo usable without a paid model key. */
@Component
public class DeterministicEmbeddingService implements EmbeddingService {
    private final int dimension;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;

    public DeterministicEmbeddingService(@Value("${intelhub.ai.embedding.dimension:384}") int dimension,
                                        @Value("${intelhub.ai.embedding.base-url:}") String baseUrl,
                                        @Value("${intelhub.ai.embedding.api-key:}") String apiKey,
                                        @Value("${intelhub.ai.embedding.model:}") String model,
                                        ObjectMapper mapper) {
        this.dimension = dimension;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public float[] embed(String text) {
        if (!baseUrl.isBlank() && model != null && !model.isBlank()) {
            try {
                var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                var payload = mapper.writeValueAsBytes(Map.of("model", model, "input", text, "dimensions", dimension));
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings"))
                        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
                if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
                var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var vector = mapper.readTree(response.body()).path("data").path(0).path("embedding");
                    if (vector.isArray() && vector.size() == dimension) {
                        float[] result = new float[dimension];
                        for (int i = 0; i < dimension; i++) result[i] = (float) vector.get(i).asDouble();
                        return result;
                    }
                }
            } catch (Exception ignored) {
                // Keep the demo usable when an embedding provider is unavailable.
            }
        }
        return localEmbedding(text);
    }

    private float[] localEmbedding(String text) {
        float[] vector = new float[dimension];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (token.isBlank()) continue;
            boolean chinese = token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
            if (chinese && token.length() > 1) {
                for (int i = 0; i < token.length() - 1; i++) addFeature(vector, token.substring(i, i + 2), 1f);
                if (token.length() > 3) for (int i = 0; i < token.length() - 2; i++) addFeature(vector, token.substring(i, i + 3), 0.35f);
            } else addFeature(vector, token, 1f);
        }
        double norm = 0;
        for (float value : vector) norm += value * value;
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) vector[i] *= scale;
        }
        return vector;
    }

    private void addFeature(float[] vector, String feature, float weight) {
        byte[] hash = digest(feature);
        int index = Math.floorMod(readInt(hash, 0), dimension);
        float sign = (hash[4] & 1) == 0 ? 1f : -1f;
        vector[index] += sign * weight;
        int second = Math.floorMod(readInt(hash, 8), dimension);
        vector[second] += sign * weight * 0.5f;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public boolean remoteConfigured() {
        return !baseUrl.isBlank() && model != null && !model.isBlank();
    }

    @Override
    public String provider() {
        return remoteConfigured() ? "OpenAI-compatible" : "本地确定性向量";
    }

    @Override
    public String model() {
        return remoteConfigured() ? model : "local-deterministic";
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 255) << 24 | (data[offset + 1] & 255) << 16 | (data[offset + 2] & 255) << 8 | data[offset + 3] & 255;
    }
}
