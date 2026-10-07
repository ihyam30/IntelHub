package com.intelhub.rag;

import com.intelhub.common.CurrentUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagChatDisconnectHttpIntegrationTest {
    private static final UUID USER_ID = UUID.fromString("21ae780b-7f81-42a1-8e22-6ca8b4517bb0");
    private ConfigurableApplicationContext serverContext;
    private CountDownLatch sendAfterClientClose;
    private CountDownLatch cancellationObserved;

    @BeforeAll
    void startServer() {
        serverContext = new SpringApplicationBuilder(TestApplication.class)
                .properties("server.port=0", "spring.config.name=rag-chat-disconnect-test", "spring.main.banner-mode=off")
                .run();
    }

    @AfterAll
    void stopServer() {
        if (serverContext != null) serverContext.close();
    }

    @Test
    void realHttpClientDisconnectRaisesSseCancellationCallback() throws Exception {
        CurrentUser currentUser = serverContext.getBean(CurrentUser.class);
        JdbcTemplate jdbc = serverContext.getBean(JdbcTemplate.class);
        RagChatService chatService = serverContext.getBean(RagChatService.class);
        CountDownLatch firstEventSent = new CountDownLatch(1);
        sendAfterClientClose = new CountDownLatch(1);
        cancellationObserved = new CountDownLatch(1);
        when(currentUser.id()).thenReturn(USER_ID);
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn("USER");
        doAnswer(invocation -> {
            SseEmitter emitter = invocation.getArgument(3);
            AtomicBoolean cancelled = invocation.getArgument(4);
            try {
                emitter.send(SseEmitter.event().name("status").data("ready"));
                firstEventSent.countDown();
                if (!sendAfterClientClose.await(5, TimeUnit.SECONDS)) throw new AssertionError("test client did not close the stream");
                try {
                    emitter.send(SseEmitter.event().name("token").data("disconnect probe"));
                } catch (IOException disconnected) {
                    // The MVC emitter invokes its registered onError callback for this failed socket write.
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!cancelled.get() && System.nanoTime() < deadline) Thread.sleep(10);
                if (cancelled.get()) cancellationObserved.countDown();
                emitter.complete();
            } catch (Exception failure) {
                if (cancelled.get()) cancellationObserved.countDown();
                throw failure;
            }
            return null;
        }).when(chatService).stream(any(UUID.class), anyBoolean(), any(RagChatController.ChatRequest.class),
                any(SseEmitter.class), any(AtomicBoolean.class));

        int port = ((WebServerApplicationContext) serverContext).getWebServer().getPort();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat/stream"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"knowledgeBaseId\":\"" + UUID.randomUUID() + "\",\"question\":\"disconnect test\"}"))
                .build();
        HttpResponse<InputStream> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(firstEventSent.await(3, TimeUnit.SECONDS)).isTrue();
        String event = readUntil(response.body(), "ready", Duration.ofSeconds(3));
        assertThat(event).contains("event:status").contains("ready");

        response.body().close();
        sendAfterClientClose.countDown();

        assertThat(cancellationObserved.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static String readUntil(InputStream input, String marker, Duration timeout) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && buffer.size() < 2048) {
            if (input.available() == 0) {
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while reading SSE", interrupted);
                }
            }
            int value = input.read();
            if (value < 0) break;
            buffer.write(value);
            String text = buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (text.contains(marker)) return text;
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({RagChatController.class, TestBeans.class})
    static class TestApplication {}

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean CurrentUser currentUser() { return mock(CurrentUser.class); }
        @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
        @Bean RagChatService ragChatService() { return mock(RagChatService.class); }
        @Bean(name = "chatExecutor", destroyMethod = "shutdownNow") ExecutorService chatExecutor() {
            return Executors.newSingleThreadExecutor();
        }
    }
}
