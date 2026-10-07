package com.intelhub.ingestion;

import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
public class IngestionRunController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final DocumentIngestionProducer producer;
    public IngestionRunController(JdbcTemplate jdbc, CurrentUser currentUser, DocumentIngestionProducer producer) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.producer = producer;
    }

    @GetMapping("/documents/{documentId}/runs")
    ApiResult<List<RunView>> runs(@PathVariable UUID documentId) {
        UUID owner = currentUser.id();
        boolean permitted = currentUser.isAdmin()
                ? jdbc.queryForObject("SELECT count(*) FROM document_record WHERE id=?", Integer.class, documentId) > 0
                : jdbc.queryForObject("SELECT count(*) FROM document_record WHERE id=? AND owner_id=?", Integer.class, documentId, owner) > 0;
        if (!permitted) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "文档不存在");
        return ApiResult.ok(jdbc.query("SELECT r.id,r.status,r.current_node,r.error_message,r.started_at,r.finished_at FROM ingestion_run r WHERE r.document_id=? ORDER BY r.started_at DESC",
                (rs, n) -> new RunView(rs.getObject("id", UUID.class).toString(), rs.getString("status"), rs.getString("current_node"), rs.getString("error_message"), rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class), List.of()), documentId)
                .stream().map(run -> new RunView(run.id(), run.status(), run.currentNode(), run.error(), run.startedAt(), run.finishedAt(),
                        jdbc.query("SELECT node_type,status,duration_ms,error_message,started_at,finished_at FROM ingestion_node_run WHERE ingestion_run_id=? ORDER BY started_at",
                                (rs, n) -> new NodeRunView(rs.getString("node_type"), rs.getString("status"), rs.getLong("duration_ms"), rs.getString("error_message"), rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class)), UUID.fromString(run.id())))).toList());
    }

    @GetMapping("/ingestion-runs")
    ApiResult<List<RecentRunView>> recent(@org.springframework.web.bind.annotation.RequestParam UUID pipelineId,
                                          @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int limit) {
        UUID owner = currentUser.id();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String sql = "SELECT r.id,r.status,r.current_node,r.error_message,r.started_at,r.finished_at,d.filename,COALESCE(p.name,'默认知识入库流程') pipeline_name " +
                "FROM ingestion_run r JOIN document_record d ON d.id=r.document_id LEFT JOIN ingestion_pipeline p ON p.id=r.pipeline_id " +
                "WHERE (r.pipeline_id=? OR (r.pipeline_id IS NULL AND EXISTS(SELECT 1 FROM ingestion_pipeline dp WHERE dp.id=? AND dp.is_default=TRUE AND dp.owner_id IS NULL))) " +
                (currentUser.isAdmin() ? "" : "AND d.owner_id=? ") +
                "ORDER BY r.started_at DESC LIMIT ?";
        List<RecentRunView> result = currentUser.isAdmin()
                ? jdbc.query(sql, (rs, n) -> recentRun(rs), pipelineId, pipelineId, safeLimit)
                : jdbc.query(sql, (rs, n) -> recentRun(rs), pipelineId, pipelineId, owner, safeLimit);
        return ApiResult.ok(result);
    }

    private RecentRunView recentRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID runId = rs.getObject("id", UUID.class);
        List<NodeRunView> nodeRuns = jdbc.query("SELECT node_type,status,duration_ms,error_message,started_at,finished_at FROM ingestion_node_run WHERE ingestion_run_id=? ORDER BY started_at",
                (node, n) -> new NodeRunView(node.getString("node_type"), node.getString("status"), node.getLong("duration_ms"), node.getString("error_message"), node.getObject("started_at", OffsetDateTime.class), node.getObject("finished_at", OffsetDateTime.class)), runId);
        return new RecentRunView(runId.toString(), rs.getString("filename"), rs.getString("pipeline_name"), rs.getString("status"),
                rs.getString("current_node"), rs.getString("error_message"), rs.getObject("started_at", OffsetDateTime.class), nodeRuns);
    }

    @PostMapping("/documents/{documentId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResult<Void> retry(@PathVariable UUID documentId) {
        UUID owner = currentUser.id();
        int updated = currentUser.isAdmin()
                ? jdbc.update("UPDATE document_record SET status='QUEUED',error_message=NULL,updated_at=now() WHERE id=? AND status='FAILED'", documentId)
                : jdbc.update("UPDATE document_record SET status='QUEUED',error_message=NULL,updated_at=now() WHERE id=? AND owner_id=? AND status='FAILED'", documentId, owner);
        if (updated == 0) {
            Integer visible = currentUser.isAdmin()
                    ? jdbc.queryForObject("SELECT count(*) FROM document_record WHERE id=?", Integer.class, documentId)
                    : jdbc.queryForObject("SELECT count(*) FROM document_record WHERE id=? AND owner_id=?", Integer.class, documentId, owner);
            if (visible == null || visible == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或无权操作");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有处理失败的文档可以重试");
        }
        try {
            producer.enqueue(documentId);
        } catch (Exception exception) {
            jdbc.update("UPDATE document_record SET status='FAILED',error_message='入库任务投递失败',updated_at=now() WHERE id=?", documentId);
            throw exception;
        }
        return ApiResult.ok();
    }

    public record RunView(String id, String status, String currentNode, String error, OffsetDateTime startedAt, OffsetDateTime finishedAt, List<NodeRunView> nodes) {}
    public record RecentRunView(String id, String documentName, String pipelineName, String status, String currentNode, String error, OffsetDateTime startedAt, List<NodeRunView> nodes) {}
    public record NodeRunView(String type, String status, long durationMs, String error, OffsetDateTime startedAt, OffsetDateTime finishedAt) {}
}
