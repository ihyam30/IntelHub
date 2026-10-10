package com.intelhub.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChunkerNode implements IngestionNode {
    @Override public String type() { return "chunk"; }

    @Override
    public void execute(IngestionContext context) {
        execute(context, ChunkerSettings.defaults());
    }

    @Override
    public void execute(IngestionContext context, ChunkerSettings configuredSettings) {
        ChunkerSettings settings = configuredSettings == null ? ChunkerSettings.defaults() : configuredSettings.withDefaults();
        int chunkSize = settings.chunkSize();
        int overlapSize = settings.overlapSize();
        if (chunkSize < 1 || chunkSize > ChunkerSettings.MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("切分长度必须在 1 到 " + ChunkerSettings.MAX_CHUNK_SIZE + " 个字符之间");
        }
        if (overlapSize < 0 || overlapSize > ChunkerSettings.maxOverlapSize(chunkSize)) {
            throw new IllegalArgumentException("重叠长度不能超过切分长度的 50%（当前最多 "
                    + ChunkerSettings.maxOverlapSize(chunkSize) + " 个字符）");
        }

        String source = context.text().replace("\r\n", "\n").trim();
        List<String> chunks = splitAtParagraphBoundaries(source, chunkSize, overlapSize);
        if (chunks.isEmpty()) chunks.add(source.substring(0, Math.min(chunkSize, source.length())));
        context.chunks(chunks);
    }

    private List<String> splitAtParagraphBoundaries(String source, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        if (source.isEmpty()) return chunks;

        int start = 0;
        while (start < source.length()) {
            int end = Math.min(start + chunkSize, source.length());
            if (end < source.length()) {
                int paragraphBoundary = source.lastIndexOf("\n\n", end);
                int minimumBoundary = start + Math.max(1, chunkSize / 2);
                if (paragraphBoundary >= minimumBoundary) end = paragraphBoundary;
            }

            String chunk = source.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (end >= source.length()) break;

            int chunkLength = end - start;
            int minimumAdvance = Math.max(1, Math.min(chunkLength, chunkSize - overlapSize));
            int nextStart = Math.max(start + minimumAdvance, end - overlapSize);
            start = nextStart;
        }
        return chunks;
    }
}
