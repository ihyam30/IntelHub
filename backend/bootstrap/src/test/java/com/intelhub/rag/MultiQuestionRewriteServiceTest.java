package com.intelhub.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.ai.ChatModelRouter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiQuestionRewriteServiceTest {
    private final ChatModelRouter router = mock(ChatModelRouter.class);
    private final MultiQuestionRewriteService service = new MultiQuestionRewriteService(router, new ObjectMapper());

    @Test
    void acceptsModelRewriteAndQuestionDecomposition() throws Exception {
        when(router.complete(anyString(), anyString())).thenReturn("""
                {"rewritten_question":"请分别说明请假流程和报销材料","sub_questions":["员工请假如何申请？","报销需要哪些材料？"]}
                """);

        var result = service.rewrite("请假怎么申请，报销要什么材料？");

        assertThat(result.rewrittenQuestion()).isEqualTo("请分别说明请假流程和报销材料");
        assertThat(result.subQuestions()).containsExactly("员工请假如何申请？", "报销需要哪些材料？");
    }

    @Test
    void stripsCodeFenceAndCapsDuplicateSubQuestions() throws Exception {
        when(router.complete(anyString(), anyString())).thenReturn("""
                ```json
                {"rewrite":"问题","sub_questions":["问题一","问题一","问题二","问题三","问题四","问题五"]}
                ```
                """);

        var result = service.rewrite("原始问题");

        assertThat(result.subQuestions()).containsExactly("问题一？", "问题二？", "问题三？", "问题四？");
    }

    @Test
    void fallsBackToQuestionBoundariesAndSafeConjunctionSplits() throws Exception {
        when(router.complete(anyString(), anyString())).thenReturn("not json");

        var result = service.rewrite("员工请假怎么申请？报销需要什么材料？");

        assertThat(result.subQuestions()).containsExactly("员工请假怎么申请？", "报销需要什么材料？");
    }

    @Test
    void onlySplitsConjoinedClausesWhenBothLookLikeQuestions() throws Exception {
        when(router.complete(anyString(), anyString())).thenReturn("");

        var result = service.rewrite("员工请假怎么申请，同时报销需要什么材料");

        assertThat(result.subQuestions()).containsExactly("员工请假怎么申请？", "报销需要什么材料？");
        assertThat(MultiQuestionRewriteService.splitByRules("请准备身份证以及银行卡"))
                .containsExactly("请准备身份证以及银行卡？");
    }

    @Test
    void returnsOneQuestionWhenThereIsNoReliableSplit() throws Exception {
        when(router.complete(anyString(), anyString())).thenReturn("");

        assertThat(service.rewrite("请介绍公司请假制度").subQuestions()).containsExactly("请介绍公司请假制度？");
    }

    @Test
    void keepsStatementContextAttachedToTheFollowingQuestion() throws Exception {
        when(router.complete(anyString(), anyString())).thenReturn("");

        var result = service.rewrite("我刚入职。年假有几天？");

        assertThat(result.subQuestions()).containsExactly("我刚入职。年假有几天？");
    }

    @Test
    void keepsExclamatoryContextAttachedWhenModelReturnsInvalidJson() throws Exception {
        when(router.complete(anyString(), anyString())).thenReturn("not json");

        var result = service.rewrite("太好了！年假有几天？");

        assertThat(result.subQuestions()).containsExactly("太好了！年假有几天？");
    }

    @Test
    void keepsExclamatoryContextAttachedWhenModelIsUnavailable() throws Exception {
        when(router.complete(anyString(), anyString())).thenThrow(new IllegalStateException("model unavailable"));

        var result = service.rewrite("太好了！年假有几天？");

        assertThat(result.subQuestions()).containsExactly("太好了！年假有几天？");
    }

    @Test
    void keepsContextWithItsQuestionWhileStillSplittingAnotherIndependentQuestion() throws Exception {
        when(router.complete(anyString(), anyString())).thenReturn("");

        var result = service.rewrite("我刚入职。年假有几天？另外，病假怎么申请？");

        assertThat(result.subQuestions()).containsExactly("我刚入职。年假有几天？", "病假怎么申请？");
    }
}
