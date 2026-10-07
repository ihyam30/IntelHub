package com.intelhub.knowledge;

import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import com.intelhub.storage.ObjectStorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/knowledge-bases")
public class KnowledgeController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectStorageService storage;
    private final ElasticsearchChunkIndex keywordIndex;

    public KnowledgeController(JdbcTemplate jdbc, CurrentUser currentUser, ObjectStorageService storage,
                               ElasticsearchChunkIndex keywordIndex) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.storage = storage;
        this.keywordIndex = keywordIndex;
    }

    @GetMapping
    ApiResult<List<KnowledgeBaseView>> list() {
        UUID userId = currentUser.id();
        String sql = currentUser.isAdmin()
                ? "SELECT k.id,k.owner_id,k.name,k.description,k.created_at,(SELECT count(*) FROM document_record d WHERE d.knowledge_base_id=k.id) documents FROM knowledge_base k ORDER BY k.created_at DESC"
                : "SELECT k.id,k.owner_id,k.name,k.description,k.created_at,(SELECT count(*) FROM document_record d WHERE d.knowledge_base_id=k.id) documents FROM knowledge_base k WHERE k.owner_id=? ORDER BY k.created_at DESC";
        List<KnowledgeBaseView> rows = currentUser.isAdmin()
                ? jdbc.query(sql, (rs, n) -> new KnowledgeBaseView(rs.getObject("id", UUID.class).toString(), rs.getObject("owner_id", UUID.class).toString(), rs.getString("name"), rs.getString("description"), rs.getObject("created_at", OffsetDateTime.class), rs.getInt("documents")))
                : jdbc.query(sql, (rs, n) -> new KnowledgeBaseView(rs.getObject("id", UUID.class).toString(), rs.getObject("owner_id", UUID.class).toString(), rs.getString("name"), rs.getString("description"), rs.getObject("created_at", OffsetDateTime.class), rs.getInt("documents")), userId);
        return ApiResult.ok(rows);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResult<KnowledgeBaseView> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        UUID id = UUID.randomUUID();
        UUID ownerId = currentUser.id();
        jdbc.update("INSERT INTO knowledge_base(id,owner_id,name,description) VALUES (?,?,?,?)", id, ownerId, request.name().trim(), request.description());
        return ApiResult.ok(new KnowledgeBaseView(id.toString(), ownerId.toString(), request.name().trim(), request.description(), OffsetDateTime.now(), 0));
    }

    @PutMapping("/{id}")
    ApiResult<Void> update(@PathVariable UUID id, @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        UUID userId = currentUser.id();
        boolean admin = currentUser.isAdmin();
        String name = request.name().trim();
        String description = request.description() == null ? null : request.description().trim();
        int updated = admin
                ? jdbc.update("UPDATE knowledge_base SET name=?,description=? WHERE id=?", name, description, id)
                : jdbc.update("UPDATE knowledge_base SET name=?,description=? WHERE id=? AND owner_id=?", name, description, id, userId);
        if (updated == 0) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或无权操作");
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    ApiResult<Void> delete(@PathVariable UUID id) throws Exception {
        UUID userId = currentUser.id();
        boolean admin = currentUser.isAdmin();
        Integer accessible = admin
                ? jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id=?", Integer.class, id)
                : jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id=? AND owner_id=?", Integer.class, id, userId);
        if (accessible == null || accessible == 0) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或无权操作");
        UUID ownerId = admin
                ? jdbc.queryForObject("SELECT owner_id FROM knowledge_base WHERE id=?", UUID.class, id)
                : userId;

        // Remove external indexes and files before cascading the database rows. If an external
        // deletion fails, metadata remains available so a retry can finish the cleanup.
        keywordIndex.deleteKnowledgeBase(id);
        storage.deletePrefix(ownerId + "/" + id + "/");

        int deleted = admin
                ? jdbc.update("DELETE FROM knowledge_base WHERE id=?", id)
                : jdbc.update("DELETE FROM knowledge_base WHERE id=? AND owner_id=?", id, userId);
        if (deleted == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "知识库不存在或无权操作");
        return ApiResult.ok();
    }

    public record CreateKnowledgeBaseRequest(@NotBlank @Size(max = 160) String name, @Size(max = 2000) String description) {}
    public record UpdateKnowledgeBaseRequest(@NotBlank @Size(max = 160) String name, @Size(max = 2000) String description) {}
    public record KnowledgeBaseView(String id, String ownerId, String name, String description, OffsetDateTime createdAt, int documents) {}
}
