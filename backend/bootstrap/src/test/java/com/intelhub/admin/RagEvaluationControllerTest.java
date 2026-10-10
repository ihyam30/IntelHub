package com.intelhub.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.common.CurrentUser;
import com.intelhub.rag.RagChatService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagEvaluationControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("5b9fe97b-a053-474e-8ded-176f9c2e1041");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("84c30591-1f02-45d8-bf1b-92647cb4f1ba");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final RagChatService ragChatService = mock(RagChatService.class);
    private final RagEvaluationController controller = new RagEvaluationController(jdbc, currentUser, new ObjectMapper(), ragChatService);

    @Test
    void datasetIsVersionedAndIncludesTheSmallSmokeQuestionSet() {
        var dataset = controller.cases().data();

        assertThat(dataset.version()).isEqualTo("1.0");
        assertThat(dataset.cases()).hasSize(6);
        assertThat(dataset.cases()).extracting(RagEvaluationController.EvaluationCase::id)
                .contains("leave-application-steps", "security-incident-reporting", "ticket-root-cause");
    }

    @Test
    void evaluationIsAdminOnly() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "需要管理员权限"))
                .when(currentUser).requireAdmin();

        assertThatThrownBy(controller::cases).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc, ragChatService);
    }

    @Test
    void runUsesRetrievalOnlyAndComputesRepeatableRecallMetrics() {
        when(currentUser.id()).thenReturn(ADMIN_ID);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(UUID.class))).thenReturn(1);
        doAnswer(invocation -> {
            String question = invocation.getArgument(3);
            String filename = question.contains("年假") ? "employee-leave-policy.md"
                    : question.contains("安全") || question.contains("数据泄露") ? "fictional-information-security-policy.pdf"
                    : "fictional-ticket-TKT-2026-0418.md";
            String evidence = question.contains("补交") ? "下一个工作日内补交门户申请，尽快告知直属负责人"
                    : question.contains("年假") ? "直属负责人审批，人事服务岗核对年假余额"
                    : question.contains("数据泄露") ? "15 分钟内通过安全工单队列提交事件"
                    : question.contains("工单") && question.contains("根因") ? "同步发送导致 result-publish-queue 持续增长"
                    : question.contains("P1") ? "连续 10 分钟超过 400 条，延迟高于 1 秒"
                    : "发生时间、涉及系统、资料级别、已观察到的影响、已采取的措施";
            return List.of(new RagChatService.Citation("chunk-1", "document-1", filename, 0, evidence, 0.9));
        }).when(ragChatService).retrieveForEvaluation(eq(KNOWLEDGE_BASE_ID), eq(ADMIN_ID), anyBoolean(), anyString());

        var result = controller.run(new RagEvaluationController.EvaluationRunRequest(KNOWLEDGE_BASE_ID)).data();

        assertThat(result.metrics().caseCount()).isEqualTo(6);
        assertThat(result.metrics().passedCases()).isEqualTo(6);
        assertThat(result.metrics().sourceHitAt1()).isEqualTo(1.0);
        assertThat(result.metrics().sourceRecallAt6()).isEqualTo(1.0);
        assertThat(result.metrics().mrrAt6()).isEqualTo(1.0);
        assertThat(result.metrics().evidenceCoverage()).isEqualTo(1.0);
    }

    @Test
    void partialExpectedEvidenceDoesNotPassCase() {
        when(currentUser.id()).thenReturn(ADMIN_ID);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(UUID.class))).thenReturn(1);
        doAnswer(invocation -> {
            String question = invocation.getArgument(3);
            String filename = question.contains("年假") ? "employee-leave-policy.md"
                    : question.contains("安全") || question.contains("数据泄露") ? "fictional-information-security-policy.pdf"
                    : "fictional-ticket-TKT-2026-0418.md";
            String evidence = question.contains("工单") && !question.contains("根因") && !question.contains("P1")
                    ? "发生时间"
                    : question.contains("补交") ? "下一个工作日内补交门户申请，尽快告知直属负责人"
                    : question.contains("年假") ? "直属负责人审批，人事服务岗核对年假余额"
                    : question.contains("数据泄露") ? "15 分钟内通过安全工单队列提交事件"
                    : question.contains("工单") && question.contains("根因") ? "同步发送导致 result-publish-queue 持续增长"
                    : question.contains("P1") ? "连续 10 分钟超过 400 条，延迟高于 1 秒"
                    : "发生时间、涉及系统、资料级别、已观察到的影响、已采取的措施";
            return List.of(new RagChatService.Citation("chunk-1", "document-1", filename, 0, evidence, 0.9));
        }).when(ragChatService).retrieveForEvaluation(eq(KNOWLEDGE_BASE_ID), eq(ADMIN_ID), anyBoolean(), anyString());

        var result = controller.run(new RagEvaluationController.EvaluationRunRequest(KNOWLEDGE_BASE_ID)).data();

        var partialResult = result.results().stream()
                .filter(item -> item.id().equals("security-incident-ticket-fields"))
                .findFirst().orElseThrow();
        assertThat(partialResult.matchedEvidence()).containsExactly("发生时间");
        assertThat(partialResult.passed()).isFalse();
        assertThat(result.metrics().passedCases()).isEqualTo(5);
    }

    @Test
    void rejectsUnknownKnowledgeBaseBeforeStartingRetrieval() {
        when(currentUser.id()).thenReturn(ADMIN_ID);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(UUID.class))).thenReturn(0);

        assertThatThrownBy(() -> controller.run(new RagEvaluationController.EvaluationRunRequest(KNOWLEDGE_BASE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("知识库不存在");
        verifyNoInteractions(ragChatService);
    }
}
