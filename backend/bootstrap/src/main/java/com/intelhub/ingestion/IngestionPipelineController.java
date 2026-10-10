package com.intelhub.ingestion;

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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/ingestion-pipelines")
public class IngestionPipelineController {
    private static final Set<String> NODE_TYPES = Set.of("parse", "normalize", "chunk", "metadata", "embedding", "index");
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper;

    public IngestionPipelineController(JdbcTemplate jdbc, CurrentUser currentUser, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    @GetMapping
    ApiResult<List<PipelineView>> list() {
        UUID owner = currentUser.id();
        List<PipelineView> rows = jdbc.query("SELECT id,owner_id,name,nodes,is_default,created_at,updated_at FROM ingestion_pipeline WHERE is_default=TRUE OR owner_id=? ORDER BY is_default DESC,updated_at DESC",
                (rs, n) -> new PipelineView(rs.getObject("id", UUID.class).toString(), rs.getObject("owner_id") == null ? null : rs.getObject("owner_id", UUID.class).toString(),
                        rs.getString("name"), parseNodes(rs.getString("nodes")), rs.getBoolean("is_default"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)), owner);
        return ApiResult.ok(rows);
    }

    @GetMapping("/nodes")
    ApiResult<List<NodeType>> nodes() {
        currentUser.id();
        return ApiResult.ok(List.of(new NodeType("parse", "文档解析", "提取 PDF、DOC/DOCX、Markdown、TXT 正文"),
                new NodeType("normalize", "文本规范化", "清理空白、换行和不可见字符"),
                new NodeType("chunk", "文本切分", "按段落与长度生成有重叠的文本块"),
                new NodeType("metadata", "元数据增强", "补充来源、文件名与处理时间"),
                new NodeType("embedding", "文本向量化", "为每个文本块生成 Embedding 向量"),
                new NodeType("index", "向量与关键词索引", "写入 pgvector 和 Elasticsearch")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResult<PipelineView> create(@Valid @RequestBody PipelineRequest request) throws Exception {
        UUID owner = currentUser.id();
        validateNodes(request.nodes());
        UUID id = UUID.randomUUID();
        List<NodeStep> nodes = applyChunkerDefaults(request.nodes());
        jdbc.update("INSERT INTO ingestion_pipeline(id,owner_id,name,nodes) VALUES (?,?,?,?::jsonb)", id, owner, request.name().trim(), mapper.writeValueAsString(nodes));
        return ApiResult.ok(new PipelineView(id.toString(), owner.toString(), request.name().trim(), nodes, false, OffsetDateTime.now(), OffsetDateTime.now()));
    }

    @PutMapping("/{id}")
    ApiResult<PipelineView> update(@PathVariable UUID id, @Valid @RequestBody PipelineRequest request) throws Exception {
        UUID owner = currentUser.id();
        validateNodes(request.nodes());
        PipelineView existing = findPipeline(id);
        List<NodeStep> nodes = applyChunkerDefaults(request.nodes());
        if (existing.isDefault()) {
            currentUser.requireAdmin();
            if (!existing.name().equals(request.name().trim()) || !nodeTypes(existing.nodes()).equals(nodeTypes(nodes))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认流程仅允许调整切分参数");
            }
            jdbc.update("UPDATE ingestion_pipeline SET nodes=?::jsonb,updated_at=now() WHERE id=? AND is_default=TRUE",
                    mapper.writeValueAsString(nodes), id);
        } else {
            int updated = jdbc.update("UPDATE ingestion_pipeline SET name=?,nodes=?::jsonb,updated_at=now() WHERE id=? AND owner_id=? AND is_default=FALSE",
                    request.name().trim(), mapper.writeValueAsString(nodes), id, owner);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程不存在或不可编辑");
        }
        return ApiResult.ok(findPipeline(id));
    }

    @DeleteMapping("/{id}")
    ApiResult<Void> delete(@PathVariable UUID id) {
        int deleted = jdbc.update("DELETE FROM ingestion_pipeline WHERE id=? AND owner_id=? AND is_default=FALSE", id, currentUser.id());
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程不存在或不可删除");
        return ApiResult.ok();
    }

    private void validateNodes(List<NodeStep> nodes) {
        if (nodes == null || nodes.size() < 4 || nodes.size() > 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "流程须以 parse 开始、以 index 结束，并按顺序包含 chunk 和 embedding；normalize 和 metadata 可选");
        }
        List<String> types = nodes.stream().map(node -> node == null ? null : node.type()).toList();
        boolean invalid = types.stream().anyMatch(type -> type == null || !NODE_TYPES.contains(type))
                || types.stream().distinct().count() != types.size()
                || !"parse".equals(types.get(0)) || !"index".equals(types.get(types.size() - 1))
                || types.indexOf("chunk") < 0 || types.indexOf("embedding") < 0
                || types.indexOf("chunk") > types.indexOf("embedding")
                || types.indexOf("normalize") >= 0 && types.indexOf("normalize") > types.indexOf("chunk");
        if (invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "流程须以 parse 开始、以 index 结束，并按顺序包含 chunk 和 embedding；normalize 和 metadata 可选");
        }
        for (NodeStep node : nodes) {
            if (node.settings() != null && !"chunk".equals(node.type())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有文本切分节点可以配置 settings");
            }
            if ("chunk".equals(node.type())) {
                ChunkerSettings settings = node.settings() == null ? ChunkerSettings.defaults() : node.settings().withDefaults();
                if (settings.chunkSize() < 1 || settings.chunkSize() > ChunkerSettings.MAX_CHUNK_SIZE) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "切分长度必须在 1 到 " + ChunkerSettings.MAX_CHUNK_SIZE + " 个字符之间");
                }
                if (settings.overlapSize() < 0
                        || settings.overlapSize() > ChunkerSettings.maxOverlapSize(settings.chunkSize())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "重叠长度不能超过切分长度的 50%（当前最多 "
                                    + ChunkerSettings.maxOverlapSize(settings.chunkSize()) + " 个字符）");
                }
            }
        }
    }

    private List<NodeStep> applyChunkerDefaults(List<NodeStep> nodes) {
        return nodes.stream().map(node -> "chunk".equals(node.type())
                ? new NodeStep(node.type(), node.settings() == null ? ChunkerSettings.defaults() : node.settings().withDefaults())
                : node).toList();
    }

    private List<NodeStep> parseNodes(String json) {
        try { return applyChunkerDefaults(mapper.readValue(json, new TypeReference<List<NodeStep>>() {})); }
        catch (Exception ignored) { return List.of(new NodeStep("parse"), new NodeStep("normalize"), new NodeStep("chunk", ChunkerSettings.defaults()), new NodeStep("metadata"), new NodeStep("embedding"), new NodeStep("index")); }
    }

    private PipelineView findPipeline(UUID id) {
        List<PipelineView> rows = jdbc.query("SELECT id,owner_id,name,nodes,is_default,created_at,updated_at FROM ingestion_pipeline WHERE id=?",
                (rs, n) -> new PipelineView(rs.getObject("id", UUID.class).toString(),
                        rs.getObject("owner_id") == null ? null : rs.getObject("owner_id", UUID.class).toString(),
                        rs.getString("name"), parseNodes(rs.getString("nodes")), rs.getBoolean("is_default"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程不存在或不可编辑");
        return rows.get(0);
    }

    private List<String> nodeTypes(List<NodeStep> nodes) {
        return nodes.stream().map(NodeStep::type).toList();
    }

    public record PipelineRequest(@NotBlank @Size(max = 160) String name, @NotEmpty List<NodeStep> nodes) {}
    public record NodeStep(@NotBlank String type, ChunkerSettings settings) {
        public NodeStep(String type) { this(type, null); }
    }
    public record NodeType(String type, String name, String description) {}
    public record PipelineView(String id, String ownerId, String name, List<NodeStep> nodes, boolean isDefault, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
