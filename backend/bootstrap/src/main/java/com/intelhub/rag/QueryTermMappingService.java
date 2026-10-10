package com.intelhub.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class QueryTermMappingService {
    private static final Logger log = LoggerFactory.getLogger(QueryTermMappingService.class);
    private static final int MAX_ADDED_TERMS = 8;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public QueryTermMappingService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public String expand(String query) {
        if (query == null || query.isBlank()) return query;
        List<MappingRule> rules;
        try {
            rules = jdbc.query("SELECT source_term,target_terms FROM query_term_mapping WHERE enabled=TRUE ORDER BY length(source_term) DESC,source_term",
                    (rs, row) -> new MappingRule(rs.getString("source_term"), parseTerms(rs.getString("target_terms"))));
        } catch (RuntimeException exception) {
            log.warn("Unable to load query term mappings; continuing with original question", exception);
            return query;
        }
        if (rules == null || rules.isEmpty()) return query;
        return expandRules(query, rules);
    }

    String expandRules(String query, List<MappingRule> rules) {
        if (query == null || query.isBlank() || rules == null || rules.isEmpty()) return query;
        String normalized = query.toLowerCase(Locale.ROOT);
        Set<String> additions = new LinkedHashSet<>();
        for (MappingRule rule : rules) {
            if (rule.sourceTerm() == null || rule.sourceTerm().isBlank()
                    || !normalized.contains(rule.sourceTerm().toLowerCase(Locale.ROOT))) continue;
            for (String target : rule.targetTerms()) {
                if (target == null || target.isBlank() || normalized.contains(target.toLowerCase(Locale.ROOT))) continue;
                additions.add(target.trim());
                if (additions.size() >= MAX_ADDED_TERMS) break;
            }
            if (additions.size() >= MAX_ADDED_TERMS) break;
        }
        return additions.isEmpty() ? query : query + " " + String.join(" ", additions);
    }

    private List<String> parseTerms(String json) {
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    record MappingRule(String sourceTerm, List<String> targetTerms) {}
}
