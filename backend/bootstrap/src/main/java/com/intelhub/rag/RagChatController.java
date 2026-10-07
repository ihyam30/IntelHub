package com.intelhub.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class RagChatController {
    private final RagChatService chatService;
    private final CurrentUser currentUser;
    private final Executor chatExecutor;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public RagChatController(RagChatService chatService, CurrentUser currentUser, @Qualifier("chatExecutor") Executor chatExecutor,
                             org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.chatService = chatService;
        this.currentUser = currentUser;
        this.chatExecutor = chatExecutor;
        this.jdbc = jdbc;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        UUID userId = currentUser.id();
        String role = jdbc.queryForObject("SELECT role FROM app_user WHERE id=?", String.class, userId);
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> { cancelled.set(true); emitter.complete(); });
        emitter.onError(error -> cancelled.set(true));
        chatExecutor.execute(() -> chatService.stream(userId, "ADMIN".equals(role), request, emitter, cancelled));
        return emitter;
    }

    public record ChatRequest(@NotNull UUID knowledgeBaseId, UUID conversationId,
                              @NotBlank @Size(max = 8000) String question,
                              @Size(max = 64) String clarificationAnswer) {
        public ChatRequest(UUID knowledgeBaseId, UUID conversationId, String question) {
            this(knowledgeBaseId, conversationId, question, null);
        }
    }
}
