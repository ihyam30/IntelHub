package com.intelhub.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class IntentClassifier {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IntentClassifier(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public Decision analyze(String question) {
        List<IntentRule> rules = jdbc.query("SELECT id,name,description,keywords,prompt_hint FROM intent_node WHERE enabled=TRUE",
                (rs, n) -> new IntentRule(rs.getObject("id", UUID.class).toString(), rs.getString("name"), rs.getString("description"),
                        parseKeywords(rs.getString("keywords")), rs.getString("prompt_hint")));
        return analyzeRules(question, rules);
    }

    static Decision analyzeRules(String question, List<IntentRule> rules) {
        String normalized = question.toLowerCase(Locale.ROOT);
        List<Classification> ranked = rules.stream().map(rule -> {
                    List<String> matched = rule.keywords().stream()
                            .filter(word -> word != null && !word.isBlank())
                            .filter(word -> normalized.contains(word.toLowerCase(Locale.ROOT)))
                            .distinct().toList();
                    double confidence = rule.keywords().isEmpty() || matched.isEmpty() ? 0
                            : Math.min(0.99, (double) matched.size() / Math.min(rule.keywords().size(), 3));
                    return new Classification(rule.id(), rule.name(), rule.description(), rule.promptHint(), confidence, matched);
                }).sorted(Comparator.comparingDouble(Classification::confidence).reversed().thenComparing(Classification::name)).toList();
        if (rules.isEmpty()) return new Decision(null, List.of(), false);

        List<Classification> matched = ranked.stream().filter(result -> result.confidence() > 0).toList();
        if (matched.isEmpty()) return new Decision(null, clarificationOptions(ranked), true);

        Classification best = matched.get(0);
        boolean lowConfidence = best.confidence() < 0.34;
        boolean closeRunnerUp = matched.size() > 1 && best.confidence() - matched.get(1).confidence() < 0.15;
        if (lowConfidence || closeRunnerUp) {
            List<Classification> options = closeRunnerUp
                    ? matched.stream().filter(item -> best.confidence() - item.confidence() < 0.15).limit(4).toList()
                    : List.of(best);
            return new Decision(null, withGeneralOption(options), true);
        }
        return new Decision(best, List.of(), false);
    }

    private static List<Classification> clarificationOptions(List<Classification> candidates) {
        return withGeneralOption(candidates.stream().limit(4).toList());
    }

    private static List<Classification> withGeneralOption(List<Classification> candidates) {
        List<Classification> options = new java.util.ArrayList<>(candidates);
        options.add(new Classification("__general__", "通用检索", "不限定意图，直接按知识库内容检索", "", 1.0, List.of()));
        return List.copyOf(options);
    }

    private List<String> parseKeywords(String json) {
        try { return mapper.readValue(json, new TypeReference<>() {}); } catch (Exception ignored) { return List.of(); }
    }

    public record IntentRule(String id, String name, String description, List<String> keywords, String promptHint) {}
    public record Classification(String id, String name, String description, String promptHint, double confidence, List<String> matchedKeywords) {}
    public record Decision(Classification classification, List<Classification> options, boolean needsClarification) {}
}
