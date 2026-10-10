package com.intelhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleChatModelClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOpenAiCompatibleRequestAndStreamsTokensUntilDone() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(200, "data: {\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"world\"}}]}\n\n" + "data: [DONE]\n\n", requestPath, requestBody, authorization);
        var client = client();
        List<String> tokens = new ArrayList<>();

        client.stream("system", "question", tokens::add);

        assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(requestBody.get()).contains("\"model\":\"acceptance-chat\"", "\"stream\":true", "\"content\":\"question\"");
        assertThat(authorization.get()).isEqualTo("Bearer local-chat-key");
        assertThat(tokens).containsExactly("hello ", "world");
    }

    @Test
    void treatsStreamClosedBeforeDoneAsFailure() throws Exception {
        startServer(200, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n", new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        List<String> tokens = new ArrayList<>();

        assertThatThrownBy(() -> client().stream("system", "question", tokens::add))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("closed its stream before [DONE]");
        assertThat(tokens).containsExactly("partial");
    }

    @Test
    void redactsCredentialsAndBoundsProviderErrorBody() throws Exception {
        String providerError = "upstream echoed local-chat-key and Bearer another-secret " + "x".repeat(5000);
        startServer(401, providerError, new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> client().stream("system", "question", ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("[REDACTED]")
                .hasMessageContaining("…")
                .hasMessageNotContaining("local-chat-key")
                .hasMessageNotContaining("another-secret")
                .hasMessageNotContaining("x".repeat(5000));
    }

    private void startServer(int status, String body, AtomicReference<String> requestPath,
                             AtomicReference<String> requestBody, AtomicReference<String> authorization) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private OpenAiCompatibleChatModelClient client() {
        return new OpenAiCompatibleChatModelClient("primary", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "local-chat-key", "acceptance-chat", new ObjectMapper());
    }
}
