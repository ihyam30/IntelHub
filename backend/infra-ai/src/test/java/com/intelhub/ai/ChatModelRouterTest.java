package com.intelhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

class ChatModelRouterTest {
    private final List<HttpServer> servers = new ArrayList<>();
    private ExecutorService executor;

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void prefersPrimaryAndOnlyUsesFallbackWhenPrimaryFailsBeforeFirstToken() throws Exception {
        var fixture = new Fixture();
        fixture.primaryHandler.set(exchange -> fixture.respond(exchange, 200, sse("primary")));
        fixture.fallbackHandler.set(exchange -> fixture.respond(exchange, 200, sse("fallback")));
        List<String> tokens = new ArrayList<>();

        assertThat(fixture.router(3, 30_000).stream("system", "question", tokens::add)).isEqualTo("primary");
        assertThat(tokens).containsExactly("primary");
        assertThat(fixture.primaryCalls.get()).isEqualTo(1);
        assertThat(fixture.fallbackCalls.get()).isZero();

        fixture.primaryHandler.set(exchange -> fixture.respond(exchange, 503, "unavailable"));
        tokens.clear();
        assertThat(fixture.router.stream("system", "question", tokens::add)).isEqualTo("fallback");
        assertThat(tokens).containsExactly("fallback");
        assertThat(fixture.primaryCalls.get()).isEqualTo(2);
        assertThat(fixture.fallbackCalls.get()).isEqualTo(1);

        fixture.primaryHandler.set(exchange -> fixture.respond(exchange, 200,
                "data: {\"choices\":[{\"delta\":{}}]}\n\n"));
        tokens.clear();
        assertThat(fixture.router.stream("system", "question", tokens::add)).isEqualTo("fallback");
        assertThat(tokens).containsExactly("fallback");
        assertThat(fixture.fallbackCalls.get()).isEqualTo(2);
    }

    @Test
    void doesNotSwitchModelsAfterFirstTokenWasEmitted() throws Exception {
        var fixture = new Fixture();
        fixture.primaryHandler.set(exchange -> fixture.respond(exchange, 200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"));
        fixture.fallbackHandler.set(exchange -> fixture.respond(exchange, 200, sse("fallback")));
        List<String> tokens = new ArrayList<>();

        assertThatThrownBy(() -> fixture.router(3, 30_000).stream("system", "question", tokens::add))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("closed its stream before [DONE]");
        assertThat(tokens).containsExactly("partial");
        assertThat(fixture.primaryCalls.get()).isEqualTo(1);
        assertThat(fixture.fallbackCalls.get()).isZero();
    }

    @Test
    void opensCircuitAfterThresholdAllowsOnlyOneHalfOpenProbeAndClosesAfterRecovery() throws Exception {
        var fixture = new Fixture();
        fixture.primaryHandler.set(exchange -> fixture.respond(exchange, 503, "unavailable"));
        fixture.fallbackHandler.set(exchange -> fixture.respond(exchange, 200, sse("fallback")));
        ChatModelRouter router = fixture.router(3, 60_000);

        for (int i = 0; i < 3; i++) assertThat(router.stream("system", "question", ignored -> {})).isEqualTo("fallback");
        assertThat(fixture.state(router, "primary")).isEqualTo("OPEN");
        assertThat(fixture.failures(router, "primary")).isEqualTo(3);
        ChatModelRouter secondInstance = fixture.router(3, 80);
        assertThat(fixture.state(secondInstance, "primary")).isEqualTo("OPEN");
        assertThat(secondInstance.stream("system", "shared-circuit", ignored -> {})).isEqualTo("fallback");
        assertThat(fixture.primaryCalls.get()).isEqualTo(3);

        CountDownLatch probeReceived = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        fixture.primaryHandler.set(exchange -> {
            probeReceived.countDown();
            try {
                if (!releaseProbe.await(3, TimeUnit.SECONDS)) throw new IOException("probe test timed out");
                fixture.respond(exchange, 200, sse("primary-recovered"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        });
        fixture.expireCircuit("primary");
        executor = Executors.newSingleThreadExecutor();
        CompletableFuture<String> probe = CompletableFuture.supplyAsync(() -> {
            try { return router.stream("system", "probe", ignored -> {}); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        }, executor);
        assertThat(probeReceived.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.state(router, "primary")).isEqualTo("HALF_OPEN");

        assertThat(router.stream("system", "concurrent", ignored -> {})).isEqualTo("fallback");
        assertThat(fixture.primaryCalls.get()).isEqualTo(4);
        assertThat(fixture.fallbackCalls.get()).isEqualTo(5);

        releaseProbe.countDown();
        assertThat(probe.get(3, TimeUnit.SECONDS)).isEqualTo("primary");
        assertThat(fixture.state(router, "primary")).isEqualTo("CLOSED");
        assertThat(fixture.failures(router, "primary")).isZero();
    }

    private static String sse(String token) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + token + "\"}}]}\n\n" + "data: [DONE]\n\n";
    }

    private final class Fixture {
        private final AtomicReference<HttpHandler> primaryHandler = new AtomicReference<>();
        private final AtomicReference<HttpHandler> fallbackHandler = new AtomicReference<>();
        private final AtomicInteger primaryCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private final Map<String, Map<Object, Object>> hashes = new ConcurrentHashMap<>();
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        private final String primaryUrl;
        private final String fallbackUrl;
        private final ChatModelRouter router;

        @SuppressWarnings("unchecked")
        private Fixture() throws Exception {
            primaryUrl = startServer(primaryHandler, primaryCalls);
            fallbackUrl = startServer(fallbackHandler, fallbackCalls);
            when(redis.opsForHash()).thenReturn((HashOperations) hashOperations);
            when(redis.opsForValue()).thenReturn(valueOperations);
            when(hashOperations.get(anyString(), any())).thenAnswer(call -> hashes
                    .getOrDefault(call.getArgument(0), Map.of()).get(call.getArgument(1)));
            doAnswer(call -> {
                hashes.computeIfAbsent(call.getArgument(0), ignored -> new ConcurrentHashMap<>())
                        .put(call.getArgument(1), call.getArgument(2));
                return null;
            }).when(hashOperations).put(anyString(), any(), any());
            when(hashOperations.increment(anyString(), any(), anyLong())).thenAnswer(call -> {
                Map<Object, Object> hash = hashes.computeIfAbsent(call.getArgument(0), ignored -> new ConcurrentHashMap<>());
                Object field = call.getArgument(1);
                long next = Long.parseLong(String.valueOf(hash.getOrDefault(field, "0"))) + (Long) call.getArgument(2);
                hash.put(field, Long.toString(next));
                return next;
            });
            when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call ->
                    values.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
            when(redis.delete(anyString())).thenAnswer(call -> values.remove(call.getArgument(0)) != null);
            router = router(3, 30_000);
        }

        private String startServer(AtomicReference<HttpHandler> handler, AtomicInteger calls) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                HttpHandler current = handler.get();
                if (current == null) respond(exchange, 503, "provider not configured");
                else current.handle(exchange);
            });
            server.start();
            servers.add(server);
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private ChatModelRouter router(int threshold, long cooldown) {
            return new ChatModelRouter(new ObjectMapper(), primaryUrl, "", "test-primary", fallbackUrl, "", "test-fallback",
                    redis, threshold, cooldown);
        }

        private void expireCircuit(String id) {
            hashes.computeIfAbsent("intelhub:model:circuit:" + id, ignored -> new ConcurrentHashMap<>())
                    .put("openUntil", Long.toString(System.currentTimeMillis() - 1));
        }

        private String state(ChatModelRouter target, String id) {
            return target.health().stream().filter(item -> id.equals(item.get("id"))).map(item -> (String) item.get("state")).findFirst().orElseThrow();
        }

        private long failures(ChatModelRouter target, String id) {
            return target.health().stream().filter(item -> id.equals(item.get("id"))).map(item -> ((Number) item.get("failures")).longValue()).findFirst().orElseThrow();
        }
    }
}
