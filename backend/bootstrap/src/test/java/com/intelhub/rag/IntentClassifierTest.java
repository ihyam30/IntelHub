package com.intelhub.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.UUID;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentClassifierTest {
    private final List<IntentClassifier.IntentRule> rules = List.of(
            new IntentClassifier.IntentRule("it", "IT 支持", "系统与网络", List.of("系统", "网络", "登录"), "检查故障步骤"),
            new IntentClassifier.IntentRule("hr", "人事制度", "员工制度", List.of("制度", "员工", "请假"), "说明适用范围"));

    @Test
    void choosesAConfidentUniqueIntentWithoutInterruptingTheChat() {
        IntentClassifier.Decision decision = IntentClassifier.analyzeRules("员工请假制度在哪里", rules);

        assertThat(decision.needsClarification()).isFalse();
        assertThat(decision.classification().id()).isEqualTo("hr");
        assertThat(decision.classification().matchedKeywords()).contains("员工", "请假", "制度");
    }

    @Test
    void asksTheUserWhenSeveralIntentRulesMatchEqually() {
        IntentClassifier.Decision decision = IntentClassifier.analyzeRules("系统制度应该怎么处理", rules);

        assertThat(decision.needsClarification()).isTrue();
        assertThat(decision.classification()).isNull();
        assertThat(decision.options()).extracting(IntentClassifier.Classification::id).contains("it", "hr", "__general__");
    }

    @Test
    void offersConfiguredIntentsAndGeneralSearchWhenNoRuleMatches() {
        IntentClassifier.Decision decision = IntentClassifier.analyzeRules("请介绍一下这个项目", rules);

        assertThat(decision.needsClarification()).isTrue();
        assertThat(decision.options()).extracting(IntentClassifier.Classification::name)
                .contains("IT 支持", "人事制度", "通用检索");
    }

    @Test
    void leavesGenericSearchAvailableWhenNoIntentRulesAreConfigured() {
        IntentClassifier.Decision decision = IntentClassifier.analyzeRules("任意问题", List.of());

        assertThat(decision.needsClarification()).isFalse();
        assertThat(decision.classification()).isNull();
        assertThat(decision.options()).isEmpty();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void loadsKnowledgeBaseTargetsWithIntentRules() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        UUID intentId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        when(row.getObject("id", UUID.class)).thenReturn(intentId);
        when(row.getString("name")).thenReturn("人事制度");
        when(row.getString("description")).thenReturn("员工制度");
        when(row.getString("keywords")).thenReturn("[\"请假\"]");
        when(row.getString("prompt_hint")).thenReturn("解释适用范围");
        when(row.getString("knowledge_base_ids")).thenReturn("[\"" + knowledgeBaseId + "\"]");
        org.mockito.Mockito.doAnswer(invocation -> {
            RowMapper<IntentClassifier.IntentRule> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class));

        IntentClassifier.Decision decision = new IntentClassifier(jdbc, new ObjectMapper()).analyze("员工请假制度在哪里");

        Assertions.assertNotNull(decision.classification());
        assertThat(decision.classification().knowledgeBaseIds()).containsExactly(knowledgeBaseId);
    }

    @Test
    void automaticRoutingSkipsLowConfidenceIntent() throws Exception {
        IntentClassifier classifier = classifierWithRules(List.of(
                new IntentClassifier.IntentRule(UUID.randomUUID().toString(), "IT 支持", "", List.of("系统", "网络", "登录"), "")));

        assertThat(classifier.classifyBest("系统怎么处理？")).isNull();
    }

    @Test
    void automaticRoutingSkipsAmbiguousIntentInsteadOfChoosingTheFirstOption() throws Exception {
        IntentClassifier classifier = classifierWithRules(List.of(
                new IntentClassifier.IntentRule(UUID.randomUUID().toString(), "IT 支持", "", List.of("系统", "网络", "登录"), ""),
                new IntentClassifier.IntentRule(UUID.randomUUID().toString(), "人事制度", "", List.of("制度", "员工", "请假"), "")));

        assertThat(classifier.classifyBest("系统制度怎么处理？")).isNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private IntentClassifier classifierWithRules(List<IntentClassifier.IntentRule> rules) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        List<ResultSet> rows = new ArrayList<>();
        for (IntentClassifier.IntentRule rule : rules) {
            ResultSet row = mock(ResultSet.class);
            when(row.getObject("id", UUID.class)).thenReturn(UUID.fromString(rule.id()));
            when(row.getString("name")).thenReturn(rule.name());
            when(row.getString("description")).thenReturn(rule.description());
            when(row.getString("keywords")).thenReturn(mapper.writeValueAsString(rule.keywords()));
            when(row.getString("prompt_hint")).thenReturn(rule.promptHint());
            when(row.getString("knowledge_base_ids")).thenReturn("[]");
            rows.add(row);
        }
        org.mockito.Mockito.doAnswer(invocation -> {
            RowMapper<IntentClassifier.IntentRule> rowMapper = invocation.getArgument(1);
            List<IntentClassifier.IntentRule> result = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) result.add(rowMapper.mapRow(rows.get(index), index));
            return result;
        }).when(jdbc).query(anyString(), any(RowMapper.class));
        return new IntentClassifier(jdbc, mapper);
    }
}
