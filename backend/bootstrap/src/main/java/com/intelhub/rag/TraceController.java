package com.intelhub.rag;

import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/traces")
public class TraceController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public TraceController(JdbcTemplate jdbc, CurrentUser currentUser) { this.jdbc = jdbc; this.currentUser = currentUser; }

    @GetMapping
    ApiResult<List<TraceView>> list(@RequestParam(defaultValue = "50") int limit) {
        UUID owner = currentUser.id();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        String sql = currentUser.isAdmin()
                ? "SELECT t.id,t.owner_id,u.username,t.conversation_id,t.query_text,t.retrieval,t.model_id,t.status,t.duration_ms,t.error_message,t.created_at FROM rag_trace t JOIN app_user u ON u.id=t.owner_id ORDER BY t.created_at DESC LIMIT ?"
                : "SELECT t.id,t.owner_id,u.username,t.conversation_id,t.query_text,t.retrieval,t.model_id,t.status,t.duration_ms,t.error_message,t.created_at FROM rag_trace t JOIN app_user u ON u.id=t.owner_id WHERE t.owner_id=? ORDER BY t.created_at DESC LIMIT ?";
        List<TraceView> result = currentUser.isAdmin()
                ? jdbc.query(sql, (rs, n) -> map(rs), safeLimit)
                : jdbc.query(sql, (rs, n) -> map(rs), owner, safeLimit);
        return ApiResult.ok(result);
    }

    private TraceView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TraceView(rs.getObject("id", UUID.class).toString(), rs.getObject("owner_id", UUID.class).toString(), rs.getString("username"),
                rs.getObject("conversation_id") == null ? null : rs.getObject("conversation_id", UUID.class).toString(), rs.getString("query_text"),
                rs.getString("retrieval"), rs.getString("model_id"), rs.getString("status"), rs.getLong("duration_ms"), rs.getString("error_message"), rs.getObject("created_at", OffsetDateTime.class));
    }

    public record TraceView(String id, String ownerId, String username, String conversationId, String query, String retrieval,
                            String modelId, String status, long durationMs, String error, OffsetDateTime createdAt) {}
}
