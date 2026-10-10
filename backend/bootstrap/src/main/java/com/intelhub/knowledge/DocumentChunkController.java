package com.intelhub.knowledge;

import com.intelhub.ai.EmbeddingService;
import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/documents/{documentId}/chunks")
public class DocumentChunkController {
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final EmbeddingService embeddings;
    private final ElasticsearchChunkIndex keywordIndex;

    public DocumentChunkController(JdbcTemplate jdbc, CurrentUser currentUser,
                                   EmbeddingService embeddings, ElasticsearchChunkIndex keywordIndex) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.embeddings = embeddings;
        this.keywordIndex = keywordIndex;
    }

    @GetMapping
    public ApiResult<ChunkPage> list(@PathVariable UUID documentId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int pageSize) {
        validatePagination(page, pageSize);
        DocumentInfo document = accessibleDocument(documentId);
        if (!"COMPLETED".equals(document.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文档入库完成后才能查看分块");
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE document_id=?", Long.class, documentId);
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, pageSize);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码超出范围");
        }
        List<ChunkView> chunks = jdbc.query(
                "SELECT id,chunk_index,content,page_number,updated_at,manually_edited FROM document_chunk WHERE document_id=? ORDER BY chunk_index LIMIT ? OFFSET ?",
                (rs, row) -> new ChunkView(rs.getObject("id", UUID.class).toString(), documentId.toString(),
                        rs.getInt("chunk_index"), rs.getString("content"),
                        (Integer) rs.getObject("page_number"), rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getBoolean("manually_edited")),
                documentId, pageSize, offset);
        return ApiResult.ok(new ChunkPage(chunks, page, pageSize, total == null ? 0 : total));
    }

    @PutMapping("/{chunkId}")
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<ChunkView> update(@PathVariable UUID documentId, @PathVariable UUID chunkId,
                                       @Valid @RequestBody UpdateChunkRequest request) throws IOException {
        DocumentInfo document = accessibleDocument(documentId);
        if (!"COMPLETED".equals(document.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文档入库完成后才能编辑分块");
        }

        Integer exists;
        try {
            exists = jdbc.queryForObject("SELECT 1 FROM document_chunk WHERE id=? AND document_id=?", Integer.class, chunkId, documentId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分块不存在或不属于该文档");
        }
        if (exists == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分块不存在或不属于该文档");

        String content = request.content().trim();
        float[] vector = embeddings.embed(content);
        if (vector == null || vector.length != embeddings.dimension()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Embedding 服务返回的向量维度无效");
        }
        List<ChunkView> updatedRows = jdbc.query(
                "UPDATE document_chunk SET content=?,embedding=?::vector,updated_at=now(),manually_edited=TRUE WHERE id=? AND document_id=? RETURNING id,chunk_index,content,page_number,updated_at,manually_edited",
                (rs, row) -> new ChunkView(rs.getObject("id", UUID.class).toString(), documentId.toString(),
                        rs.getInt("chunk_index"), rs.getString("content"),
                        (Integer) rs.getObject("page_number"), rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getBoolean("manually_edited")),
                content, Arrays.toString(vector), chunkId, documentId);
        if (updatedRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分块不存在或不属于该文档");

        keywordIndex.index(chunkId, documentId, document.knowledgeBaseId(), document.ownerId(), document.filename(), content);
        return ApiResult.ok(updatedRows.get(0));
    }

    private DocumentInfo accessibleDocument(UUID documentId) {
        String sql = "SELECT d.filename,d.status,d.owner_id,d.knowledge_base_id FROM document_record d WHERE d.id=?";
        Object[] args = currentUser.isAdmin()
                ? new Object[]{documentId}
                : new Object[]{documentId, currentUser.id()};
        if (!currentUser.isAdmin()) sql += " AND d.owner_id=?";
        List<DocumentInfo> rows = jdbc.query(sql,
                (rs, row) -> new DocumentInfo(rs.getString("filename"), rs.getString("status"),
                        rs.getObject("owner_id", UUID.class), rs.getObject("knowledge_base_id", UUID.class)), args);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或无权访问");
        return rows.get(0);
    }

    private static void validatePagination(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数无效，页码须大于 0，单页最多 100 条");
        }
    }

    private record DocumentInfo(String filename, String status, UUID ownerId, UUID knowledgeBaseId) {}

    public record ChunkPage(List<ChunkView> items, int page, int pageSize, long total) {}

    public record ChunkView(String id, String documentId, int chunkIndex, String content, Integer pageNumber,
                            OffsetDateTime updatedAt, boolean manuallyEdited) {}

    public record UpdateChunkRequest(@NotBlank(message = "分块内容不能为空") @Size(max = 20000, message = "分块内容不能超过 20000 个字符") String content) {}
}
