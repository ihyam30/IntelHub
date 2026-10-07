package com.intelhub.ingestion;

import org.springframework.stereotype.Component;

@Component
public class NormalizeNode implements IngestionNode {
    @Override public String type() { return "normalize"; }
    @Override public void execute(IngestionContext context) {
        String normalized = context.text().replace('\u0000', ' ').replaceAll("[\\t ]+", " ").replaceAll(" *\\n *", "\n").trim();
        context.text(normalized);
    }
}
