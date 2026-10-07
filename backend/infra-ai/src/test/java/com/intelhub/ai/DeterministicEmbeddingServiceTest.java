package com.intelhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicEmbeddingServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void usesAnOpenAiCompatibleEmbeddingEndpointWhenConfigured() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(200, "{\"data\":[{\"embedding\":[0.25,-0.5]}]}", requestBody, authorization);
        var service = service();

        float[] vector = service.embed("sample chunk");

        assertThat(vector).containsExactly(0.25f, -0.5f);
        assertThat(requestBody.get()).contains("\"model\":\"acceptance-embedding\"", "\"input\":\"sample chunk\"", "\"dimensions\":2");
        assertThat(authorization.get()).isEqualTo("Bearer local-test-key");
        assertThat(service.remoteConfigured()).isTrue();
        assertThat(service.provider()).isEqualTo("OpenAI-compatible");
        assertThat(service.model()).isEqualTo("acceptance-embedding");
    }

    @Test
    void fallsBackToStableLocalVectorsWhenProviderIsUnavailable() throws Exception {
        startServer(503, "provider unavailable", new AtomicReference<>(), new AtomicReference<>());
        var service = service();

        float[] first = service.embed("stable fallback text");
        float[] second = service.embed("stable fallback text");

        assertThat(first).hasSize(2).containsExactly(second);
        assertThat((double) first[0] * first[0] + (double) first[1] * first[1]).isGreaterThan(0d);
        assertThat(service.remoteConfigured()).isTrue();
        assertThat(service.provider()).isEqualTo("OpenAI-compatible");
    }

    @Test
    void reportsLocalModeWhenNoProviderIsConfigured() {
        var service = new DeterministicEmbeddingService(4, "", "", "", new ObjectMapper());

        assertThat(service.remoteConfigured()).isFalse();
        assertThat(service.provider()).isEqualTo("本地确定性向量");
        assertThat(service.model()).isEqualTo("local-deterministic");
        assertThat(service.dimension()).isEqualTo(4);
    }

    private void startServer(int status, String body, AtomicReference<String> requestBody,
                             AtomicReference<String> authorization) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    private DeterministicEmbeddingService service() {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new DeterministicEmbeddingService(2, endpoint, "local-test-key", "acceptance-embedding", new ObjectMapper());
    }
}
