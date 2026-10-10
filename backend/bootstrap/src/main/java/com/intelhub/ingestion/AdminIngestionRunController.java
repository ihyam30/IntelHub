package com.intelhub.ingestion;

import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/ingestion-runs")
public class AdminIngestionRunController {
    private static final String RUN_QUERY = "SELECT r.id,r.document_id,d.filename,d.owner_id,u.username,p.name pipeline_name," +
            "r.status,r.current_node,r.error_message,r.started_at,r.finished_at " +
            "FROM ingestion_run r JOIN document_record d ON d.id=r.document_id " +
            "JOIN app_user u ON u.id=d.owner_id LEFT JOIN ingestion_pipeline p ON p.id=r.pipeline_id " +
            "WHERE (CAST(? AS VARCHAR) IS NULL OR r.status=?) AND (?='' OR d.filename ILIKE '%'||?||'%' OR u.username ILIKE '%'||?||'%') " +
            "ORDER BY r.started_at DESC LIMIT ?";

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public AdminIngestionRunController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResult<List<TaskView>> list(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "") String query,
                                          @RequestParam(defaultValue = "100") int limit) {
        currentUser.requireAdmin();
        String normalizedStatus = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalizedStatus != null && !List.of("QUEUED", "PROCESSING", "COMPLETED", "FAILED").contains(normalizedStatus)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "无效的任务状态");
        }
        String search = query == null ? "" : query.trim();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<TaskRow> rows = jdbc.query(RUN_QUERY,
                (rs, row) -> new TaskRow(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("filename"), rs.getObject("owner_id", UUID.class), rs.getString("username"),
                        rs.getString("pipeline_name"), rs.getString("status"), rs.getString("current_node"),
                        rs.getString("error_message"), rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class)),
                normalizedStatus, normalizedStatus, search, search, search, safeLimit);
        return ApiResult.ok(rows.stream().map(row -> new TaskView(row.id().toString(), row.documentId().toString(),
                row.filename(), row.ownerId().toString(), row.username(),
                row.pipelineName() == null ? "默认知识入库流程" : row.pipelineName(), row.status(), row.currentNode(),
                row.error(), row.startedAt(), row.finishedAt(), loadNodes(row.id()))).toList());
    }

    private List<TaskNodeView> loadNodes(UUID runId) {
        return jdbc.query("SELECT node_type,status,duration_ms,error_message,started_at,finished_at " +
                        "FROM ingestion_node_run WHERE ingestion_run_id=? ORDER BY started_at",
                (rs, row) -> new TaskNodeView(rs.getString("node_type"), rs.getString("status"),
                        rs.getLong("duration_ms"), rs.getString("error_message"),
                        rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class)), runId);
    }

    private record TaskRow(UUID id, UUID documentId, String filename, UUID ownerId, String username,
                           String pipelineName, String status, String currentNode, String error,
                           OffsetDateTime startedAt, OffsetDateTime finishedAt) {}

    public record TaskNodeView(String type, String status, long durationMs, String error,
                               OffsetDateTime startedAt, OffsetDateTime finishedAt) {}

    public record TaskView(String id, String documentId, String documentName, String ownerId, String username,
                           String pipelineName, String status, String currentNode, String error,
                           OffsetDateTime startedAt, OffsetDateTime finishedAt, List<TaskNodeView> nodes) {}
}
