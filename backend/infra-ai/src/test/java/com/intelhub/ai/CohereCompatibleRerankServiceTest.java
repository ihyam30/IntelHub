package com.intelhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CohereCompatibleRerankServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsCohereCompatibleRequestAndParsesRankedIndexes() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(200, "{\"results\":[{\"index\":1,\"relevance_score\":0.98},{\"index\":0,\"relevance_score\":0.71}]}", requestBody, authorization);
        CohereCompatibleRerankService service = service();

        var ranked = service.rerank("policy question", java.util.List.of("first passage", "second passage", "third passage"), 6);

        assertThat(service.configured()).isTrue();
        assertThat(service.provider()).isEqualTo("Cohere-compatible");
        assertThat(ranked).containsExactly(new RerankService.RankedDocument(1, 0.98), new RerankService.RankedDocument(0, 0.71));
        assertThat(requestBody.get()).contains("\"model\":\"acceptance-reranker\"", "\"query\":\"policy question\"",
                "\"documents\":[\"first passage\",\"second passage\",\"third passage\"]", "\"top_n\":3");
        assertThat(authorization.get()).isEqualTo("Bearer local-rerank-key");
    }

    @Test
    void rejectsUnavailableRerankProviderResponse() throws Exception {
        startServer(503, "provider unavailable", new AtomicReference<>(), new AtomicReference<>());
        CohereCompatibleRerankService service = service();

        assertThatThrownBy(() -> service.rerank("query", java.util.List.of("passage"), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 503");
    }

    private void startServer(int status, String body, AtomicReference<String> requestBody,
                             AtomicReference<String> authorization) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", exchange -> {
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

    private CohereCompatibleRerankService service() {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new CohereCompatibleRerankService(endpoint, "local-rerank-key", "acceptance-reranker", new ObjectMapper());
    }
}
