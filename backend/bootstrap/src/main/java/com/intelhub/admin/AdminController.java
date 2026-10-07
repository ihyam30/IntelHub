package com.intelhub.admin;

import com.intelhub.ai.ChatModelRouter;
import com.intelhub.ai.EmbeddingService;
import com.intelhub.ai.RerankService;
import cn.dev33.satoken.stp.StpUtil;
import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import com.intelhub.rag.FairQueueLimiter;
import com.intelhub.mcp.McpToolClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ChatModelRouter modelRouter;
    private final EmbeddingService embeddingService;
    private final FairQueueLimiter limiter;
    private final StringRedisTemplate redis;
    private final McpToolClient mcpToolClient;
    private final RerankService rerankService;

    public AdminController(JdbcTemplate jdbc, CurrentUser currentUser, ChatModelRouter modelRouter, EmbeddingService embeddingService, FairQueueLimiter limiter,
                           StringRedisTemplate redis, McpToolClient mcpToolClient, RerankService rerankService) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.modelRouter = modelRouter;
        this.embeddingService = embeddingService;
        this.limiter = limiter;
        this.redis = redis;
        this.mcpToolClient = mcpToolClient;
        this.rerankService = rerankService;
    }

    @GetMapping("/users")
    ApiResult<List<UserView>> users(@RequestParam(required = false) String query) {
        currentUser.requireAdmin();
        String search = query == null ? "" : query.trim();
        return ApiResult.ok(jdbc.query("SELECT id,username,display_name,role,enabled,created_at FROM app_user WHERE username ILIKE ? OR display_name ILIKE ? ORDER BY created_at DESC LIMIT 200",
                (rs, n) -> new UserView(rs.getObject("id", UUID.class).toString(), rs.getString("username"), rs.getString("display_name"), rs.getString("role"), rs.getBoolean("enabled"), rs.getObject("created_at", OffsetDateTime.class)),
                "%" + search + "%", "%" + search + "%"));
    }

    @PatchMapping("/users/{id}/enabled")
    ApiResult<Void> setUserEnabled(@PathVariable UUID id, @RequestBody EnabledRequest request) {
        currentUser.requireAdmin();
        if (id.equals(currentUser.id()) && !request.enabled()) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "不能停用当前管理员账号");
        int rows = jdbc.update("UPDATE app_user SET enabled=?,updated_at=now() WHERE id=?", request.enabled(), id);
        if (rows == 0) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "用户不存在");
        if (!request.enabled()) StpUtil.logout(id.toString());
        return ApiResult.ok();
    }

    @GetMapping("/models")
    ApiResult<List<Map<String, Object>>> models() {
        currentUser.requireAdmin();
        return ApiResult.ok(modelRouter.health());
    }

    @GetMapping("/rerank")
    ApiResult<Map<String, Object>> rerank() {
        currentUser.requireAdmin();
        return ApiResult.ok(Map.of("configured", rerankService.configured(), "provider", rerankService.provider(), "model", rerankService.model()));
    }

    @GetMapping("/embedding")
    ApiResult<Map<String, Object>> embedding() {
        currentUser.requireAdmin();
        return ApiResult.ok(Map.of("configured", embeddingService.remoteConfigured(), "provider", embeddingService.provider(),
                "model", embeddingService.model(), "dimension", embeddingService.dimension(),
                "mode", embeddingService.remoteConfigured() ? "REMOTE_CONFIGURED_WITH_LOCAL_FALLBACK" : "LOCAL_FALLBACK"));
    }

    @GetMapping("/tools")
    ApiResult<List<String>> tools() {
        currentUser.requireAdmin();
        return ApiResult.ok(mcpToolClient.availableTools());
    }

    @GetMapping("/settings")
    ApiResult<SystemSettings> settings() {
        currentUser.requireAdmin();
        String configured = redis.opsForValue().get("intelhub:settings:max-concurrent");
        return ApiResult.ok(new SystemSettings(configured == null ? limiter.maxConcurrent() : Integer.parseInt(configured), 20, 30));
    }

    @PutMapping("/settings")
    ApiResult<SystemSettings> updateSettings(@Valid @RequestBody UpdateSettings request) {
        currentUser.requireAdmin();
        limiter.updateMaxConcurrent(request.maxConcurrent());
        return ApiResult.ok(new SystemSettings(request.maxConcurrent(), 20, 30));
    }

    @GetMapping("/dashboard")
    ApiResult<Dashboard> dashboard() {
        currentUser.requireAdmin();
        return ApiResult.ok(new Dashboard(
                jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM knowledge_base", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM document_record", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM conversation", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM rag_trace WHERE created_at >= now() - interval '24 hours'", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM document_record WHERE status='FAILED'", Long.class)));
    }

    public record UserView(String id, String username, String displayName, String role, boolean enabled, OffsetDateTime createdAt) {}
    public record EnabledRequest(boolean enabled) {}
    public record UpdateSettings(@Min(1) @Max(64) int maxConcurrent) {}
    public record SystemSettings(int maxConcurrent, int perUserRequestsPerMinute, int maxWaitSeconds) {}
    public record Dashboard(long users, long knowledgeBases, long documents, long conversations, long traces24h, long failedDocuments) {}
}
