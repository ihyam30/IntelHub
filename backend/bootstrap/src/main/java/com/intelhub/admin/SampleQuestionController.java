package com.intelhub.admin;

import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/sample-questions")
public class SampleQuestionController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public SampleQuestionController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResult<List<SampleQuestionView>> enabled() {
        currentUser.id();
        return ApiResult.ok(readQuestions(true));
    }

    @GetMapping("/admin")
    public ApiResult<List<SampleQuestionView>> list() {
        currentUser.requireAdmin();
        return ApiResult.ok(readQuestions(false));
    }

    @PostMapping
    public ApiResult<SampleQuestionView> create(@Valid @RequestBody SampleQuestionRequest request) {
        currentUser.requireAdmin();
        int nextOrder = jdbc.queryForObject("SELECT COALESCE(max(sort_order),0)+1 FROM sample_question", Integer.class);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sample_question(id,title,question,description,enabled,sort_order) VALUES (?,?,?,?,?,?)",
                id, request.title().trim(), request.question().trim(), clean(request.description()), request.enabled(), nextOrder);
        return ApiResult.ok(new SampleQuestionView(id.toString(), request.title().trim(), request.question().trim(),
                clean(request.description()), request.enabled(), nextOrder, OffsetDateTime.now()));
    }

    @PutMapping("/{id}")
    public ApiResult<SampleQuestionView> update(@PathVariable UUID id, @Valid @RequestBody SampleQuestionRequest request) {
        currentUser.requireAdmin();
        int updated = jdbc.update("UPDATE sample_question SET title=?,question=?,description=?,enabled=?,updated_at=now() WHERE id=?",
                request.title().trim(), request.question().trim(), clean(request.description()), request.enabled(), id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "示例问题不存在");
        return ApiResult.ok(jdbc.queryForObject("SELECT id,title,question,description,enabled,sort_order,updated_at FROM sample_question WHERE id=?",
                (rs, row) -> map(rs), id));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable UUID id) {
        currentUser.requireAdmin();
        if (jdbc.update("DELETE FROM sample_question WHERE id=?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "示例问题不存在");
        }
        return ApiResult.ok();
    }

    private List<SampleQuestionView> readQuestions(boolean enabledOnly) {
        String sql = "SELECT id,title,question,description,enabled,sort_order,updated_at FROM sample_question " +
                (enabledOnly ? "WHERE enabled=TRUE " : "") + "ORDER BY sort_order,id";
        return jdbc.query(sql, (rs, row) -> map(rs));
    }

    private SampleQuestionView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SampleQuestionView(rs.getObject("id", UUID.class).toString(), rs.getString("title"), rs.getString("question"),
                rs.getString("description"), rs.getBoolean("enabled"), rs.getInt("sort_order"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    public record SampleQuestionRequest(@NotBlank @Size(max = 120) String title,
                                        @NotBlank @Size(max = 1000) String question,
                                        @Size(max = 500) String description, boolean enabled) {}
    public record SampleQuestionView(String id, String title, String question, String description, boolean enabled,
                                     int sortOrder, OffsetDateTime updatedAt) {}
}
