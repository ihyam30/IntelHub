package com.intelhub.ingestion;

import com.intelhub.storage.ObjectStorageService;
import org.apache.tika.Tika;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class ParserNode implements IngestionNode {
    private final ObjectStorageService storage;
    private final Tika tika = new Tika();
    private final Parser markdownParser = Parser.builder().build();
    private final TextContentRenderer markdownRenderer = TextContentRenderer.builder().build();

    public ParserNode(ObjectStorageService storage) {
        this.storage = storage;
    }

    @Override public String type() { return "parse"; }

    @Override
    public void execute(IngestionContext context) throws Exception {
        byte[] content = storage.get(context.objectKey());
        String filename = context.filename().toLowerCase(Locale.ROOT);
        String parsed = filename.endsWith(".md") || filename.endsWith(".markdown")
                ? markdownRenderer.render(markdownParser.parse(new String(content, StandardCharsets.UTF_8)))
                : tika.parseToString(new ByteArrayInputStream(content));
        if (parsed == null || parsed.isBlank()) throw new IllegalArgumentException("文档解析后没有可索引文本");
        context.text(parsed.trim());
    }
}
