package com.intelhub.knowledge;

import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import com.intelhub.ingestion.DocumentIngestionProducer;
import com.intelhub.storage.ObjectStorageService;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
public class DocumentController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectStorageService storage;
    private final DocumentIngestionProducer producer;
    private final ElasticsearchChunkIndex keywordIndex;

    public DocumentController(JdbcTemplate jdbc, CurrentUser currentUser, ObjectStorageService storage,
                              DocumentIngestionProducer producer, ElasticsearchChunkIndex keywordIndex) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.storage = storage;
        this.producer = producer;
        this.keywordIndex = keywordIndex;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResult<DocumentView> upload(@PathVariable UUID knowledgeBaseId, @RequestPart("file") @NotNull MultipartFile file,
                                   @RequestParam(required = false) UUID pipelineId) throws Exception {
        UUID userId = currentUser.id();
        boolean owned = currentUser.isAdmin()
                ? jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id=?", Integer.class, knowledgeBaseId) > 0
                : jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id=? AND owner_id=?", Integer.class, knowledgeBaseId, userId) > 0;
        if (!owned) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或无权访问");
        if (pipelineId != null) {
            int pipelineExists = jdbc.queryForObject("SELECT count(*) FROM ingestion_pipeline WHERE id=? AND (owner_id=? OR owner_id IS NULL)", Integer.class, pipelineId, userId);
            if (pipelineExists == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入库流程不存在或无权使用");
        }
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择非空文件");
        String filename = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc") || lower.endsWith(".md") || lower.endsWith(".txt"))) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "当前支持 PDF、DOC/DOCX、Markdown 和 TXT");
        }
        UUID id = UUID.randomUUID();
        String objectKey = userId + "/" + knowledgeBaseId + "/" + id + "/" + filename.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        storage.put(objectKey, file.getBytes(), file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        jdbc.update("INSERT INTO document_record(id,knowledge_base_id,owner_id,filename,object_key,content_type,file_size,status,pipeline_id) VALUES (?,?,?,?,?,?,?,'QUEUED',?)",
                id, knowledgeBaseId, userId, filename, objectKey, file.getContentType(), file.getSize(), pipelineId);
        try {
            producer.enqueue(id);
        } catch (Exception exception) {
            // A producer timeout is ambiguous: the broker may have persisted the message
            // while the consumer already started processing. Do not overwrite that state.
            jdbc.update("UPDATE document_record SET status='FAILED',error_message='入库任务投递失败',updated_at=now() WHERE id=? AND status='QUEUED'", id);
            throw exception;
        }
        return ApiResult.ok(new DocumentView(id.toString(), knowledgeBaseId.toString(), filename, "QUEUED", 0, OffsetDateTime.now(), null));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    ApiResult<List<DocumentView>> list(@PathVariable UUID knowledgeBaseId) {
        UUID userId = currentUser.id();
        boolean permitted = currentUser.isAdmin()
                ? jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id=?", Integer.class, knowledgeBaseId) > 0
                : jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id=? AND owner_id=?", Integer.class, knowledgeBaseId, userId) > 0;
        if (!permitted) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或无权访问");
        List<DocumentView> rows = jdbc.query("SELECT id,knowledge_base_id,filename,status,chunk_count,created_at,error_message FROM document_record WHERE knowledge_base_id=? ORDER BY created_at DESC",
                (rs, n) -> new DocumentView(rs.getObject("id", UUID.class).toString(), rs.getObject("knowledge_base_id", UUID.class).toString(), rs.getString("filename"), rs.getString("status"), rs.getInt("chunk_count"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("error_message")), knowledgeBaseId);
        return ApiResult.ok(rows);
    }

    @DeleteMapping("/documents/{documentId}")
    ApiResult<Void> delete(@PathVariable UUID documentId) throws Exception {
        UUID userId = currentUser.id();
        boolean admin = currentUser.isAdmin();
        List<java.util.Map<String, Object>> rows = admin
                ? jdbc.queryForList("SELECT object_key FROM document_record WHERE id=?", documentId)
                : jdbc.queryForList("SELECT object_key FROM document_record WHERE id=? AND owner_id=?", documentId, userId);
        List<String> keys = rows.stream().map(row -> (String) row.get("object_key")).toList();
        if (keys.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或无权操作");
        keywordIndex.deleteDocument(documentId);
        storage.delete(keys.get(0));
        jdbc.update(admin
                        ? "DELETE FROM document_record WHERE id=?"
                        : "DELETE FROM document_record WHERE id=? AND owner_id=?",
                admin ? new Object[]{documentId} : new Object[]{documentId, userId});
        return ApiResult.ok();
    }

    public record DocumentView(String id, String knowledgeBaseId, String filename, String status, int chunkCount, OffsetDateTime createdAt, String error) {}
}
