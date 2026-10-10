package com.intelhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class OpenAiCompatibleChatModelClient implements ChatModelClient {
    private static final int MAX_ERROR_BODY_BYTES = 4096;

    private final String id;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenAiCompatibleChatModelClient(String id, String baseUrl, String apiKey, String model,
                                            ObjectMapper mapper) {
        this.id = id;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void stream(String systemPrompt, String userPrompt, Consumer<String> onToken) throws Exception {
        byte[] body = mapper.writeValueAsBytes(Map.of(
                "model", model,
                "stream", true,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt))));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<java.io.InputStream> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail;
            try (var input = response.body()) {
                byte[] errorBody = input.readNBytes(MAX_ERROR_BODY_BYTES + 1);
                boolean truncated = errorBody.length > MAX_ERROR_BODY_BYTES;
                detail = new String(errorBody, 0, Math.min(errorBody.length, MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
                detail = sanitizeErrorDetail(detail);
                if (truncated) detail += "…";
            }
            throw new IllegalStateException("Model " + id + " returned HTTP " + response.statusCode() + ": " + detail);
        }
        boolean completed = false;
        try (var input = response.body();
             var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    completed = true;
                    break;
                }
                JsonNode root = mapper.readTree(data);
                JsonNode content = root.path("choices").path(0).path("delta").path("content");
                if (content.isTextual() && !content.asText().isEmpty()) onToken.accept(content.asText());
            }
        }
        if (!completed) throw new IOException("Model " + id + " closed its stream before [DONE]");
    }

    private String sanitizeErrorDetail(String detail) {
        String sanitized = detail == null ? "" : detail;
        if (apiKey != null && !apiKey.isBlank()) sanitized = sanitized.replace(apiKey, "[REDACTED]");
        return sanitized.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]");
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
