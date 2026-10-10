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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ChatModelRouter modelRouter;
    private final EmbeddingService embeddingService;
    private final FairQueueLimiter limiter;
    private final StringRedisTemplate redis;
    private final McpToolClient mcpToolClient;
    private final RerankService rerankService;
    private final AdminMutationGuard adminMutationGuard;

    public AdminController(JdbcTemplate jdbc, CurrentUser currentUser, ChatModelRouter modelRouter, EmbeddingService embeddingService, FairQueueLimiter limiter,
                           StringRedisTemplate redis, McpToolClient mcpToolClient, RerankService rerankService,
                           AdminMutationGuard adminMutationGuard) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.modelRouter = modelRouter;
        this.embeddingService = embeddingService;
        this.limiter = limiter;
        this.redis = redis;
        this.mcpToolClient = mcpToolClient;
        this.rerankService = rerankService;
        this.adminMutationGuard = adminMutationGuard;
    }

    @GetMapping("/users")
    ApiResult<List<UserView>> users(@RequestParam(required = false) String query) {
        currentUser.requireAdmin();
        String search = query == null ? "" : query.trim();
        return ApiResult.ok(jdbc.query("SELECT id,username,display_name,role,enabled,created_at FROM app_user WHERE username ILIKE ? OR display_name ILIKE ? ORDER BY created_at DESC LIMIT 200",
                (rs, n) -> new UserView(rs.getObject("id", UUID.class).toString(), rs.getString("username"), rs.getString("display_name"), rs.getString("role"), rs.getBoolean("enabled"), rs.getObject("created_at", OffsetDateTime.class)),
                "%" + search + "%", "%" + search + "%"));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResult<UserView> createUser(@Valid @RequestBody CreateUserRequest request) {
        currentUser.requireAdmin();
        String username = request.username().trim();
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE lower(username)=lower(?)", Integer.class, username);
        if (existing != null && existing > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已被使用");
        String displayName = request.displayName() == null || request.displayName().isBlank() ? username : request.displayName().trim();
        String role = request.role() == null ? "USER" : request.role();
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name,role) VALUES (?,?,?,?,?)",
                id, username, passwordEncoder.encode(request.password()), displayName, role);
        return ApiResult.ok(new UserView(id.toString(), username, displayName, role, true, createdAt));
    }

    @PutMapping("/users/{id}")
    @Transactional
    public ApiResult<UserView> updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        currentUser.requireAdmin();
        return adminMutationGuard.withLock(() -> {
            currentUser.requireAdmin();
            UserStatus target = getUserStatus(id);
            if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
            UUID operatorId = currentUser.id();
            if (id.equals(operatorId) && !"ADMIN".equals(request.role())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能修改当前管理员的角色");
            }
            if (target.enabled() && "ADMIN".equals(target.role()) && "USER".equals(request.role())) {
                ensureAnotherEnabledAdministratorRemains("至少需要保留一名启用的管理员");
            }
            int updated = jdbc.update("UPDATE app_user SET display_name=?,role=?,updated_at=now() WHERE id=?",
                    request.displayName().trim(), request.role(), id);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
            return ApiResult.ok(jdbc.queryForObject("SELECT id,username,display_name,role,enabled,created_at FROM app_user WHERE id=?",
                    (rs, row) -> new UserView(rs.getObject("id", UUID.class).toString(), rs.getString("username"), rs.getString("display_name"),
                            rs.getString("role"), rs.getBoolean("enabled"), rs.getObject("created_at", OffsetDateTime.class)), id));
        });
    }

    @DeleteMapping("/users/{id}")
    @Transactional
    public ApiResult<Void> deleteUser(@PathVariable UUID id) {
        currentUser.requireAdmin();
        return adminMutationGuard.withLock(() -> {
            currentUser.requireAdmin();
            if (id.equals(currentUser.id())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除当前管理员账号");
            UserStatus target = getUserStatus(id);
            if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
            if (target.enabled() && "ADMIN".equals(target.role())) {
                ensureAnotherEnabledAdministratorRemains("不能删除最后一名启用的管理员");
            }
            jdbc.update("UPDATE app_user SET username=?,password_hash='!deleted!',display_name='已删除用户',role='USER',enabled=FALSE,updated_at=now() WHERE id=?",
                    "deleted-" + id, id);
            StpUtil.logout(id.toString());
            return ApiResult.ok();
        });
    }

    @PatchMapping("/users/{id}/enabled")
    @Transactional
    public ApiResult<Void> setUserEnabled(@PathVariable UUID id, @RequestBody EnabledRequest request) {
        currentUser.requireAdmin();
        return adminMutationGuard.withLock(() -> {
            currentUser.requireAdmin();
            if (id.equals(currentUser.id()) && !request.enabled()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能停用当前管理员账号");
            }
            UserStatus target = getUserStatus(id);
            if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
            if (!request.enabled() && target.enabled() && "ADMIN".equals(target.role())) {
                ensureAnotherEnabledAdministratorRemains("不能停用最后一名启用的管理员");
            }
            int rows = jdbc.update("UPDATE app_user SET enabled=?,updated_at=now() WHERE id=?", request.enabled(), id);
            if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
            if (!request.enabled()) StpUtil.logout(id.toString());
            return ApiResult.ok();
        });
    }

    private UserStatus getUserStatus(UUID id) {
        List<UserStatus> rows = jdbc.query("SELECT role,enabled FROM app_user WHERE id=?",
                (rs, row) -> new UserStatus(rs.getString("role"), rs.getBoolean("enabled")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void ensureAnotherEnabledAdministratorRemains(String message) {
        Integer admins = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE", Integer.class);
        if (admins == null || admins <= 1) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    record UserStatus(String role, boolean enabled) {}

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
    public record CreateUserRequest(@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_.-]{3,64}$") String username,
                                    @NotBlank @Size(min = 10, max = 128) String password,
                                    @Size(max = 100) String displayName,
                                    @Pattern(regexp = "USER|ADMIN") String role) {}
    public record UpdateUserRequest(@NotBlank @Size(max = 100) String displayName, @NotBlank @Pattern(regexp = "USER|ADMIN") String role) {}
    public record EnabledRequest(boolean enabled) {}
    public record UpdateSettings(@Min(1) @Max(64) int maxConcurrent) {}
    public record SystemSettings(int maxConcurrent, int perUserRequestsPerMinute, int maxWaitSeconds) {}
    public record Dashboard(long users, long knowledgeBases, long documents, long conversations, long traces24h, long failedDocuments) {}
}
