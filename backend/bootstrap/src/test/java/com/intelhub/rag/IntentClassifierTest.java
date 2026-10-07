package com.intelhub.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
