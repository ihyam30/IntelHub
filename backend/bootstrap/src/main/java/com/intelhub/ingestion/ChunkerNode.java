package com.intelhub.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChunkerNode implements IngestionNode {
    private static final int MAX_CHARS = 900;
    private static final int OVERLAP = 120;

    @Override public String type() { return "chunk"; }

    @Override
    public void execute(IngestionContext context) {
        String source = context.text().replace("\r\n", "\n").trim();
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : source.split("\n{2,}")) {
            String value = paragraph.trim();
            if (value.isEmpty()) continue;
            if (!current.isEmpty() && current.length() + value.length() + 2 > MAX_CHARS) {
                chunks.add(current.toString().trim());
                String tail = current.substring(Math.max(0, current.length() - OVERLAP));
                current.setLength(0);
                current.append(tail).append("\n");
            }
            for (int offset = 0; offset < value.length(); offset += MAX_CHARS) {
                String part = value.substring(offset, Math.min(value.length(), offset + MAX_CHARS));
                if (current.length() + part.length() > MAX_CHARS && !current.isEmpty()) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(part).append('\n');
            }
        }
        if (!current.isEmpty()) chunks.add(current.toString().trim());
        if (chunks.isEmpty()) chunks.add(source.substring(0, Math.min(MAX_CHARS, source.length())));
        context.chunks(chunks);
    }
}
