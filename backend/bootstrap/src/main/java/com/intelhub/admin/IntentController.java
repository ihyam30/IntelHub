package com.intelhub.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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
        return ApiResult.ok(jdbc.query("SELECT i.id,i.parent_id,i.name,i.description,i.keywords,i.prompt_hint,i.enabled,i.updated_at, " +
                        "COALESCE((SELECT jsonb_agg(m.knowledge_base_id ORDER BY m.knowledge_base_id)::text FROM intent_knowledge_base m WHERE m.intent_id=i.id), '[]') knowledge_base_ids " +
                        "FROM intent_node i ORDER BY i.parent_id NULLS FIRST,i.name",
                (rs, n) -> new IntentView(rs.getObject("id", UUID.class).toString(), uuid(rs.getObject("parent_id")), rs.getString("name"), rs.getString("description"),
                        keywords(rs.getString("keywords")), rs.getString("prompt_hint"), rs.getBoolean("enabled"), rs.getObject("updated_at", OffsetDateTime.class),
                        knowledgeBaseIds(rs.getString("knowledge_base_ids")))));
    }

    @PostMapping
    @Transactional
    ApiResult<IntentView> create(@Valid @RequestBody IntentRequest request) throws Exception {
        currentUser.requireAdmin();
        validateParent(request.parentId(), null, request.keywords());
        List<UUID> knowledgeBaseIds = safeKnowledgeBaseIds(request.knowledgeBaseIds());
        validateKnowledgeBases(knowledgeBaseIds);
        UUID id = UUID.randomUUID();
        List<String> terms = safeKeywords(request.keywords());
        jdbc.update("INSERT INTO intent_node(id,parent_id,name,description,keywords,prompt_hint,enabled) VALUES (?,?,?,?,?::jsonb,?,?)", id,
                request.parentId(), request.name().trim(), request.description(), mapper.writeValueAsString(terms), request.promptHint(), request.enabled());
        saveKnowledgeBaseMappings(id, knowledgeBaseIds);
        return ApiResult.ok(new IntentView(id.toString(), request.parentId() == null ? null : request.parentId().toString(), request.name().trim(), request.description(), terms, request.promptHint(), request.enabled(), OffsetDateTime.now(), knowledgeBaseIds));
    }

    @PutMapping("/{id}")
    @Transactional
    ApiResult<IntentView> update(@PathVariable UUID id, @Valid @RequestBody IntentRequest request) throws Exception {
        currentUser.requireAdmin();
        validateParent(request.parentId(), id, request.keywords());
        List<UUID> knowledgeBaseIds = safeKnowledgeBaseIds(request.knowledgeBaseIds());
        validateKnowledgeBases(knowledgeBaseIds);
        int count = jdbc.update("UPDATE intent_node SET parent_id=?,name=?,description=?,keywords=?::jsonb,prompt_hint=?,enabled=?,updated_at=now() WHERE id=?",
                request.parentId(), request.name().trim(), request.description(), mapper.writeValueAsString(safeKeywords(request.keywords())), request.promptHint(), request.enabled(), id);
        if (count == 0) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "意图不存在");
        saveKnowledgeBaseMappings(id, knowledgeBaseIds);
        return ApiResult.ok(jdbc.queryForObject("SELECT i.id,i.parent_id,i.name,i.description,i.keywords,i.prompt_hint,i.enabled,i.updated_at, " +
                        "COALESCE((SELECT jsonb_agg(m.knowledge_base_id ORDER BY m.knowledge_base_id)::text FROM intent_knowledge_base m WHERE m.intent_id=i.id), '[]') knowledge_base_ids " +
                        "FROM intent_node i WHERE i.id=?",
                (rs, n) -> new IntentView(rs.getObject("id", UUID.class).toString(), uuid(rs.getObject("parent_id")), rs.getString("name"), rs.getString("description"),
                        keywords(rs.getString("keywords")), rs.getString("prompt_hint"), rs.getBoolean("enabled"), rs.getObject("updated_at", OffsetDateTime.class),
                        knowledgeBaseIds(rs.getString("knowledge_base_ids"))), id));
    }

    @DeleteMapping("/{id}")
    ApiResult<Void> delete(@PathVariable UUID id) {
        currentUser.requireAdmin();
        int count = jdbc.update("DELETE FROM intent_node WHERE id=?", id);
        if (count == 0) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "意图不存在");
        return ApiResult.ok();
    }

    private List<String> keywords(String json) { try { return mapper.readValue(json, new TypeReference<>() {}); } catch (Exception ignored) { return List.of(); } }
    private List<UUID> knowledgeBaseIds(String json) { try { return mapper.readValue(json, new TypeReference<>() {}); } catch (Exception ignored) { return List.of(); } }
    private static List<String> safeKeywords(List<String> keywords) { return keywords == null ? List.of() : keywords.stream().filter(word -> word != null && !word.isBlank()).map(String::trim).distinct().toList(); }
    private static List<UUID> safeKnowledgeBaseIds(List<UUID> ids) { return ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList(); }
    private static String uuid(Object value) { return value == null ? null : value.toString(); }

    private void validateKnowledgeBases(List<UUID> ids) {
        if (ids.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Integer count = jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id IN (" + placeholders + ")", Integer.class, ids.toArray());
        if (count == null || count != ids.size()) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "定向知识库不存在");
    }

    private void saveKnowledgeBaseMappings(UUID intentId, List<UUID> ids) {
        jdbc.update("DELETE FROM intent_knowledge_base WHERE intent_id=?", intentId);
        for (UUID knowledgeBaseId : ids) {
            jdbc.update("INSERT INTO intent_knowledge_base(intent_id,knowledge_base_id) VALUES (?,?)", intentId, knowledgeBaseId);
        }
    }

    private void validateParent(UUID parentId, UUID selfId, List<String> keywords) {
        List<String> terms = safeKeywords(keywords);
        if (parentId == null) return;
        if (parentId.equals(selfId)) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "意图节点不能设为自己的父节点");
        if (selfId != null) {
            Integer childCount = jdbc.queryForObject("SELECT count(*) FROM intent_node WHERE parent_id=?", Integer.class, selfId);
            if (childCount != null && childCount > 0) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "包含子意图的根节点不能移动到子级");
        }
        List<UUID> ancestors = jdbc.query("SELECT parent_id FROM intent_node WHERE id=?",
                (rs, n) -> rs.getObject("parent_id", UUID.class), parentId);
        if (ancestors.isEmpty()) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "父意图节点不存在");
        if (ancestors.get(0) != null) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "意图树最多支持两层");
        if (terms.isEmpty()) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "子意图至少需要一个关键词");
    }

    public record IntentRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description,
                                @Size(max = 50) List<String> keywords, @Size(max = 2000) String promptHint,
                                boolean enabled, UUID parentId, @Size(max = 20) List<UUID> knowledgeBaseIds) {
        public IntentRequest(String name, String description, List<String> keywords, String promptHint, boolean enabled, UUID parentId) {
            this(name, description, keywords, promptHint, enabled, parentId, List.of());
        }
    }
    public record IntentView(String id, String parentId, String name, String description, List<String> keywords, String promptHint,
                             boolean enabled, OffsetDateTime updatedAt, List<UUID> knowledgeBaseIds) {}
}
