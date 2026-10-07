package com.intelhub.ingestion;

import com.intelhub.ai.EmbeddingService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class EmbeddingNode implements IngestionNode {
    private final EmbeddingService embeddings;

    public EmbeddingNode(EmbeddingService embeddings) {
        this.embeddings = embeddings;
    }

    @Override public String type() { return "embedding"; }

    @Override
    public void execute(IngestionContext context) {
        if (context.chunks().isEmpty()) throw new IllegalArgumentException("没有可生成向量的文本块");
        List<float[]> vectors = new ArrayList<>(context.chunks().size());
        for (String chunk : context.chunks()) {
            float[] vector = embeddings.embed(chunk);
            if (vector == null || vector.length != embeddings.dimension()) {
                throw new IllegalStateException("Embedding 维度与配置不一致");
            }
            vectors.add(vector);
        }
        context.embeddings(vectors);
    }
}
