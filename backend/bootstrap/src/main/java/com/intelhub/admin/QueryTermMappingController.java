package com.intelhub.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
@RequestMapping("/admin/query-mappings")
public class QueryTermMappingController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper;

    public QueryTermMappingController(JdbcTemplate jdbc, CurrentUser currentUser, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    @GetMapping
    public ApiResult<List<MappingView>> list() {
        currentUser.requireAdmin();
        return ApiResult.ok(jdbc.query("SELECT id,source_term,target_terms,description,enabled,updated_at FROM query_term_mapping ORDER BY updated_at DESC",
                (rs, row) -> new MappingView(rs.getObject("id", UUID.class).toString(), rs.getString("source_term"),
                        parseTerms(rs.getString("target_terms")), rs.getString("description"), rs.getBoolean("enabled"),
                        rs.getObject("updated_at", OffsetDateTime.class))));
    }

    @PostMapping
    public ApiResult<MappingView> create(@Valid @RequestBody MappingRequest request) throws Exception {
        currentUser.requireAdmin();
        List<String> terms = validateTerms(request.targetTerms());
        ensureUnique(request.sourceTerm(), null);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO query_term_mapping(id,source_term,target_terms,description,enabled) VALUES (?,?,?::jsonb,?,?)",
                id, request.sourceTerm().trim(), mapper.writeValueAsString(terms), clean(request.description()), request.enabled());
        return ApiResult.ok(new MappingView(id.toString(), request.sourceTerm().trim(), terms, clean(request.description()), request.enabled(), OffsetDateTime.now()));
    }

    @PutMapping("/{id}")
    public ApiResult<MappingView> update(@PathVariable UUID id, @Valid @RequestBody MappingRequest request) throws Exception {
        currentUser.requireAdmin();
        List<String> terms = validateTerms(request.targetTerms());
        ensureUnique(request.sourceTerm(), id);
        int updated = jdbc.update("UPDATE query_term_mapping SET source_term=?,target_terms=?::jsonb,description=?,enabled=?,updated_at=now() WHERE id=?",
                request.sourceTerm().trim(), mapper.writeValueAsString(terms), clean(request.description()), request.enabled(), id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关键词映射不存在");
        return ApiResult.ok(jdbc.queryForObject("SELECT id,source_term,target_terms,description,enabled,updated_at FROM query_term_mapping WHERE id=?",
                (rs, row) -> new MappingView(rs.getObject("id", UUID.class).toString(), rs.getString("source_term"),
                        parseTerms(rs.getString("target_terms")), rs.getString("description"), rs.getBoolean("enabled"),
                        rs.getObject("updated_at", OffsetDateTime.class)), id));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable UUID id) {
        currentUser.requireAdmin();
        if (jdbc.update("DELETE FROM query_term_mapping WHERE id=?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关键词映射不存在");
        }
        return ApiResult.ok();
    }

    private void ensureUnique(String sourceTerm, UUID excludingId) {
        String sql = "SELECT count(*) FROM query_term_mapping WHERE lower(source_term)=lower(?)" + (excludingId == null ? "" : " AND id<>?");
        Integer count = excludingId == null
                ? jdbc.queryForObject(sql, Integer.class, sourceTerm.trim())
                : jdbc.queryForObject(sql, Integer.class, sourceTerm.trim(), excludingId);
        if (count != null && count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "该原词已存在映射规则");
    }

    private static List<String> validateTerms(List<String> targets) {
        List<String> result = targets == null ? List.of() : targets.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写至少一个目标词");
        return result;
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }
    private List<String> parseTerms(String json) { try { return mapper.readValue(json, new TypeReference<>() {}); } catch (Exception ignored) { return List.of(); } }

    public record MappingRequest(@NotBlank @Size(max = 120) String sourceTerm, @NotEmpty @Size(max = 20) List<String> targetTerms,
                                 @Size(max = 500) String description, boolean enabled) {}
    public record MappingView(String id, String sourceTerm, List<String> targetTerms, String description, boolean enabled,
                              OffsetDateTime updatedAt) {}
}
