package com.intelhub.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/intents")
public class IntentController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper;

    public IntentController(JdbcTemplate jdbc, CurrentUser currentUser, ObjectMapper mapper) { this.jdbc = jdbc; this.currentUser = currentUser; this.mapper = mapper; }

    @GetMapping
    ApiResult<List<IntentView>> list() {
        currentUser.requireAdmin();
        return ApiResult.ok(jdbc.query("SELECT id,name,description,keywords,prompt_hint,enabled,updated_at FROM intent_node ORDER BY name",
                (rs, n) -> new IntentView(rs.getObject("id", UUID.class).toString(), rs.getString("name"), rs.getString("description"),
                        keywords(rs.getString("keywords")), rs.getString("prompt_hint"), rs.getBoolean("enabled"), rs.getObject("updated_at", OffsetDateTime.class))));
    }

    @PostMapping
    ApiResult<IntentView> create(@Valid @RequestBody IntentRequest request) throws Exception {
        currentUser.requireAdmin();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO intent_node(id,name,description,keywords,prompt_hint) VALUES (?,?,?,?::jsonb,?)", id,
                request.name().trim(), request.description(), mapper.writeValueAsString(request.keywords()), request.promptHint());
        return ApiResult.ok(new IntentView(id.toString(), request.name().trim(), request.description(), request.keywords(), request.promptHint(), true, OffsetDateTime.now()));
    }

    @PutMapping("/{id}")
    ApiResult<IntentView> update(@PathVariable UUID id, @Valid @RequestBody IntentRequest request) throws Exception {
        currentUser.requireAdmin();
        int count = jdbc.update("UPDATE intent_node SET name=?,description=?,keywords=?::jsonb,prompt_hint=?,enabled=?,updated_at=now() WHERE id=?",
                request.name().trim(), request.description(), mapper.writeValueAsString(request.keywords()), request.promptHint(), request.enabled(), id);
        if (count == 0) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "意图不存在");
        return ApiResult.ok(jdbc.queryForObject("SELECT id,name,description,keywords,prompt_hint,enabled,updated_at FROM intent_node WHERE id=?",
                (rs, n) -> new IntentView(rs.getObject("id", UUID.class).toString(), rs.getString("name"), rs.getString("description"), keywords(rs.getString("keywords")), rs.getString("prompt_hint"), rs.getBoolean("enabled"), rs.getObject("updated_at", OffsetDateTime.class)), id));
    }

    @DeleteMapping("/{id}")
    ApiResult<Void> delete(@PathVariable UUID id) {
        currentUser.requireAdmin();
        int count = jdbc.update("DELETE FROM intent_node WHERE id=?", id);
        if (count == 0) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "意图不存在");
        return ApiResult.ok();
    }

    private List<String> keywords(String json) { try { return mapper.readValue(json, new TypeReference<>() {}); } catch (Exception ignored) { return List.of(); } }

    public record IntentRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description,
                                @NotEmpty List<String> keywords, @Size(max = 2000) String promptHint, boolean enabled) {}
    public record IntentView(String id, String name, String description, List<String> keywords, String promptHint, boolean enabled, OffsetDateTime updatedAt) {}
}
