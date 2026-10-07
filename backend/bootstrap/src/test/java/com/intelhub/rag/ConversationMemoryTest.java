package com.intelhub.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryTest {
    @Test
    void expandsCoreferentialFollowUpWithRecentQuestionAnswerAndSummary() {
        ConversationMemory.Context context = new ConversationMemory(null).rewrite("它的报销时限是多久？", "关注：员工差旅报销",
                List.of(new ConversationMemory.Message("assistant", "员工差旅报销应在出差结束后十个工作日内提交。"),
                        new ConversationMemory.Message("user", "员工差旅如何报销？")));

        assertThat(context.rewrittenQuestion()).contains("员工差旅报销", "此前问题", "此前回答", "它的报销时限是多久");
        assertThat(context.summary()).contains("员工差旅报销");
    }

    @Test
    void keepsStandaloneQuestionsCompactWhileRetainingConversationMemoryForTheAnswerPrompt() {
        ConversationMemory.Context context = new ConversationMemory(null).rewrite("差旅报销需要哪些材料？", "关注：员工差旅报销",
                List.of(new ConversationMemory.Message("assistant", "上轮回答"), new ConversationMemory.Message("user", "之前的问题")));

        assertThat(context.rewrittenQuestion()).isEqualTo("差旅报销需要哪些材料？");
        assertThat(context.summary()).isEqualTo("关注：员工差旅报销");
    }

    @Test
    void createsRollingExtractiveMemoryFromUserGoalsAndRelevantAnswerFacts() {
        String summary = ConversationMemory.summarize("关注：员工差旅报销", List.of(
                new ConversationMemory.Message("user", "员工差旅报销需要哪些材料？"),
                new ConversationMemory.Message("assistant", "请准备发票、行程单和审批记录。提交时限是出差结束后十个工作日。"),
                new ConversationMemory.Message("user", "线上怎么提交？"),
                new ConversationMemory.Message("assistant", "登录费用系统，进入差旅报销页面并上传材料。")));

        assertThat(summary).contains("关注：员工差旅报销", "员工差旅报销需要哪些材料", "发票、行程单和审批记录", "线上怎么提交");
        assertThat(summary).contains("提交时限是出差结束后十个工作日");
    }

    @Test
    void identifiesCommonChineseAndEnglishFollowUpOpeners() {
        assertThat(ConversationMemory.isFollowUp("为什么？", List.of(new ConversationMemory.Message("user", "上一问")))).isTrue();
        assertThat(ConversationMemory.isFollowUp("How about the second option?", List.of(new ConversationMemory.Message("assistant", "回答")))).isTrue();
        assertThat(ConversationMemory.isFollowUp("请总结知识库中的主要内容", List.of(new ConversationMemory.Message("user", "上一问")))).isFalse();
    }
}
