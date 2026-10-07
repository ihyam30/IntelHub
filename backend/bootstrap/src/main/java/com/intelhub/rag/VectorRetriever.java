package com.intelhub.rag;

import com.intelhub.ai.EmbeddingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class VectorRetriever {
    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddings;

    public VectorRetriever(JdbcTemplate jdbc, EmbeddingService embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    public List<SearchHit> search(UUID knowledgeBaseId, String query, int limit) {
        String vector = Arrays.toString(embeddings.embed(query));
        return jdbc.query("SELECT c.id,c.document_id,d.filename,c.chunk_index,c.content,(c.embedding <=> ?::vector) AS distance " +
                        "FROM document_chunk c JOIN document_record d ON d.id=c.document_id " +
                        "WHERE c.knowledge_base_id=? AND d.status='COMPLETED' ORDER BY c.embedding <=> ?::vector LIMIT ?",
                (rs, n) -> new SearchHit(rs.getObject("id", UUID.class).toString(), rs.getObject("document_id", UUID.class).toString(),
                        rs.getString("filename"), rs.getInt("chunk_index"), rs.getString("content"), 1.0 - rs.getDouble("distance"), "vector"),
                vector, knowledgeBaseId, vector, limit);
    }

    public record SearchHit(String chunkId, String documentId, String filename, int chunkIndex, String content, double score, String channel) {}
}
