package com.intelhub.ingestion;

import com.intelhub.knowledge.ElasticsearchChunkIndex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class IndexerNode implements IngestionNode {
    private final JdbcTemplate jdbc;
    private final ElasticsearchChunkIndex keywordIndex;
    private final ObjectMapper mapper;

    public IndexerNode(JdbcTemplate jdbc, ElasticsearchChunkIndex keywordIndex, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.keywordIndex = keywordIndex;
        this.mapper = mapper;
    }

    @Override public String type() { return "index"; }

    @Override
    public void execute(IngestionContext context) throws Exception {
        if (context.embeddings().size() != context.chunks().size()) {
            throw new IllegalStateException("Embedding 节点未为每个文本块生成向量");
        }
        jdbc.update("UPDATE document_record SET metadata=?::jsonb WHERE id=?", mapper.writeValueAsString(context.metadata()), context.documentId());
        jdbc.update("DELETE FROM document_chunk WHERE document_id=?", context.documentId());
        keywordIndex.deleteDocument(context.documentId());
        int index = 0;
        for (String content : context.chunks()) {
            UUID chunkId = UUID.nameUUIDFromBytes((context.documentId() + ":" + index).getBytes(StandardCharsets.UTF_8));
            float[] vector = context.embeddings().get(index);
            String vectorValue = Arrays.toString(vector);
            jdbc.update("INSERT INTO document_chunk(id,document_id,knowledge_base_id,owner_id,chunk_index,content,embedding) VALUES (?,?,?,?,?,?,?::vector)",
                    chunkId, context.documentId(), context.knowledgeBaseId(), context.ownerId(), index++, content, vectorValue);
            keywordIndex.index(chunkId, context.documentId(), context.knowledgeBaseId(), context.ownerId(), context.filename(), content);
        }
    }
}
