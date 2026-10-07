package com.intelhub.rag;

import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/conversations")
public class ConversationController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public ConversationController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping
    ApiResult<List<ConversationView>> list() {
        UUID owner = currentUser.id();
        return ApiResult.ok(jdbc.query("SELECT id,knowledge_base_id,title,summary,created_at,updated_at FROM conversation WHERE owner_id=? ORDER BY updated_at DESC LIMIT 100",
                (rs, n) -> new ConversationView(rs.getObject("id", UUID.class).toString(), rs.getObject("knowledge_base_id") == null ? null : rs.getObject("knowledge_base_id", UUID.class).toString(),
                        rs.getString("title"), rs.getString("summary"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)), owner));
    }

    @GetMapping("/{id}/messages")
    ApiResult<List<MessageView>> messages(@PathVariable UUID id) {
        UUID owner = currentUser.id();
        if (jdbc.queryForObject("SELECT count(*) FROM conversation WHERE id=? AND owner_id=?", Integer.class, id, owner) == 0) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "会话不存在");
        }
        return ApiResult.ok(jdbc.query("SELECT id,role,content,citations,model_id,created_at FROM conversation_message WHERE conversation_id=? ORDER BY created_at",
                (rs, n) -> new MessageView(rs.getObject("id", UUID.class).toString(), rs.getString("role"), rs.getString("content"), rs.getString("citations"), rs.getString("model_id"), rs.getObject("created_at", OffsetDateTime.class)), id));
    }

    @DeleteMapping("/{id}")
    ApiResult<Void> delete(@PathVariable UUID id) {
        int rows = jdbc.update("DELETE FROM conversation WHERE id=? AND owner_id=?", id, currentUser.id());
        if (rows == 0) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "会话不存在");
        return ApiResult.ok();
    }

    public record ConversationView(String id, String knowledgeBaseId, String title, String summary, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record MessageView(String id, String role, String content, String citations, String modelId, OffsetDateTime createdAt) {}
}
