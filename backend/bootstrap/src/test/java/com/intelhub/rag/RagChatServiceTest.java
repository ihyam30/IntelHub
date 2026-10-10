package com.intelhub.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.ai.ChatModelRouter;
import com.intelhub.ai.RerankService;
import com.intelhub.knowledge.ElasticsearchChunkIndex;
import com.intelhub.mcp.McpToolClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagChatServiceTest {
    private static final UUID USER_ID = UUID.fromString("7d5eb1ef-06c1-47dc-a521-089e5c034ed6");
    private static final UUID KB_ID = UUID.fromString("c2d920ec-3f0c-4839-8d0e-0f7a44b5a214");

    @Test
    void parallelRetrieversRunAtTheSameTime() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch bothStarted = new CountDownLatch(2);
            AtomicBoolean bothOverlapped = new AtomicBoolean(true);
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30))).thenAnswer(invocation -> {
                bothStarted.countDown();
                if (!bothStarted.await(2, TimeUnit.SECONDS)) bothOverlapped.set(false);
                return List.of(vectorHit("vector", "Vector evidence"));
            });
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30))).thenAnswer(invocation -> {
                bothStarted.countDown();
                if (!bothStarted.await(2, TimeUnit.SECONDS)) bothOverlapped.set(false);
                return List.of(keywordHit("keyword", "Keyword evidence"));
            });

            fixture.stream();

            assertThat(bothOverlapped).isTrue();
            assertThat(bothStarted.getCount()).isZero();
        }
    }

    @Test
    void vectorFailureFallsBackToKeywordResultsAndRecordsDegradedTrace() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30))).thenThrow(new IllegalStateException("database offline"));
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30)))
                    .thenReturn(List.of(keywordHit("keyword", "keyword-only evidence")));

            fixture.stream();

            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(fixture.modelRouter).stream(anyString(), prompt.capture(), any(), any());
            assertThat(prompt.getValue()).contains("keyword-only evidence");
            JsonNode trace = fixture.traceRetrieval();
            assertThat(trace.path("vectorAvailable").asBoolean()).isFalse();
            assertThat(trace.path("keywordAvailable").asBoolean()).isTrue();
            assertThat(trace.path("vectorError").asText()).isEqualTo("IllegalStateException");
            assertThat(trace.path("keywordHits").asInt()).isEqualTo(1);
            JsonNode stages = trace.path("stages");
            assertThat(stages.findValuesAsText("name")).contains("context_rewrite", "query_rewrite", "intent_classification",
                    "vector_retrieval", "keyword_retrieval", "rrf_fusion", "rerank", "model_routing");
            JsonNode vectorStage = findStage(stages, "vector_retrieval");
            assertThat(vectorStage.path("status").asText()).isEqualTo("FAILED");
            assertThat(vectorStage.path("error").asText()).contains("IllegalStateException: database offline");
            assertThat(findStage(stages, "query_rewrite").path("status").asText()).isEqualTo("FALLBACK");
            assertThat(findStage(stages, "rerank").path("status").asText()).isEqualTo("SKIPPED");
            JsonNode modelStage = findStage(stages, "model_routing");
            assertThat(modelStage.path("details").path("attempts").get(0).path("providerId").asText()).isEqualTo("test-model");
        }
    }

    @Test
    void keywordFailureFallsBackToVectorResultsAndRecordsDegradedTrace() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30)))
                    .thenReturn(List.of(vectorHit("vector", "vector-only evidence")));
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30)))
                    .thenThrow(new IOException("elasticsearch offline"));

            fixture.stream();

            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(fixture.modelRouter).stream(anyString(), prompt.capture(), any(), any());
            assertThat(prompt.getValue()).contains("vector-only evidence");
            JsonNode trace = fixture.traceRetrieval();
            assertThat(trace.path("vectorAvailable").asBoolean()).isTrue();
            assertThat(trace.path("keywordAvailable").asBoolean()).isFalse();
            assertThat(trace.path("keywordError").asText()).isEqualTo("IOException");
            assertThat(trace.path("vectorHits").asInt()).isEqualTo(1);
        }
    }

    @Test
    void reportsUnavailableWhenBothRetrieversFail() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30))).thenThrow(new IllegalStateException("database offline"));
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30)))
                    .thenThrow(new IOException("elasticsearch offline"));

            fixture.stream();

            verify(fixture.modelRouter, never()).stream(anyString(), anyString(), any(), any());
            verify(fixture.lease).close();
            ArgumentCaptor<Object[]> traceArguments = ArgumentCaptor.forClass(Object[].class);
            verify(fixture.jdbc).update(contains("INSERT INTO rag_trace"), traceArguments.capture());
            assertThat(traceArguments.getValue()[6]).isEqualTo("FAILED");
            assertThat(traceArguments.getValue()[8].toString()).contains("检索服务暂时不可用");
        }
    }

    @Test
    void returnsConservativeAnswerWhenBothRetrieversReturnNoEvidence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30))).thenReturn(List.of());
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30))).thenReturn(List.of());

            fixture.stream();

            verify(fixture.modelRouter, never()).stream(anyString(), anyString(), any(), any());
            List<Object[]> messages = fixture.assistantMessages();
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)[2].toString()).contains("没有在这个知识库中找到足够的资料");
            assertThat(messages.get(0)[4]).isEqualTo("no-evidence");
            JsonNode trace = fixture.traceRetrieval();
            assertThat(trace.path("vectorAvailable").asBoolean()).isTrue();
            assertThat(trace.path("keywordAvailable").asBoolean()).isTrue();
            assertThat(trace.path("citations").asInt()).isZero();
        }
    }

    @Test
    void includesMcpToolResultInAnswerContextWhenKnowledgeBaseHasNoEvidence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30))).thenReturn(List.of());
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30))).thenReturn(List.of());
            when(fixture.mcp.invokeForQuestion(contains("工单")))
                    .thenReturn("演示工单汇总：待处理 12 单，处理中 8 单，已解决 36 单。");

            fixture.stream("工单目前是什么情况？");

            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(fixture.modelRouter).stream(anyString(), prompt.capture(), any(), any());
            assertThat(prompt.getValue()).contains("业务工具结果（模拟演示数据）")
                    .contains("待处理 12 单");
            assertThat(fixture.traceRetrieval().path("mcpToolUsed").asBoolean()).isTrue();
            assertThat(fixture.assistantMessages()).hasSize(1);
            verify(fixture.lease).close();
        }
    }

    @Test
    void releasesPermitWhenClientDisconnectsDuringModelStreaming() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30)))
                    .thenReturn(List.of(vectorHit("disconnect", "disconnect test evidence")));
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30)))
                    .thenReturn(List.of());
            AtomicBoolean cancelled = new AtomicBoolean();
            doAnswer(invocation -> {
                Consumer<String> onToken = invocation.getArgument(2);
                cancelled.set(true);
                onToken.accept("token after disconnect");
                return "test-model";
            }).when(fixture.modelRouter).stream(anyString(), anyString(), any(), any());

            fixture.service.stream(USER_ID, false,
                    new RagChatController.ChatRequest(KB_ID, null, "disconnect test question"),
                    new SseEmitter(30_000L), cancelled);

            verify(fixture.lease).close();
            ArgumentCaptor<Object[]> trace = ArgumentCaptor.forClass(Object[].class);
            verify(fixture.jdbc).update(contains("INSERT INTO rag_trace"), trace.capture());
            assertThat(trace.getValue()[6]).isEqualTo("FAILED");
            assertThat(trace.getValue()[8].toString()).contains("客户端已断开");
        }
    }

    @Test
    void asksForAnIntentChoiceBeforeRunningRetrievalWhenTheQuestionIsAmbiguous() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<IntentClassifier.Classification> options = List.of(
                    new IntentClassifier.Classification("intent-it", "IT 支持", "系统问题", "", 0.5, List.of("系统")),
                    new IntentClassifier.Classification("intent-hr", "人事制度", "员工制度", "", 0.5, List.of("制度")),
                    new IntentClassifier.Classification("__general__", "通用检索", "直接检索", "", 1.0, List.of()));
            when(fixture.intents.analyze(anyString())).thenReturn(new IntentClassifier.Decision(null, options, true));

            fixture.stream();

            verifyNoInteractions(fixture.vector, fixture.keyword, fixture.mcp);
            verify(fixture.modelRouter, never()).stream(anyString(), anyString(), any(), any());
            verify(fixture.jdbc).update(contains("pending_question=?"), any(Object[].class));
            ArgumentCaptor<Object[]> trace = ArgumentCaptor.forClass(Object[].class);
            verify(fixture.jdbc).update(contains("INSERT INTO rag_trace"), trace.capture());
            assertThat(trace.getValue()[6]).isEqualTo("NEEDS_CLARIFICATION");
            JsonNode retrieval = fixture.mapper.readTree(trace.getValue()[4].toString());
            assertThat(retrieval.path("clarificationRequired").asBoolean()).isTrue();
        }
    }

    @Test
    void selectedClarificationResumesTheOriginalQuestionWithTheChosenIntent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID conversationId = UUID.fromString("5c5773f4-6d48-4f6d-b3d1-b397bc6bf865");
            String intentId = "10000000-0000-0000-0000-000000000002";
            IntentClassifier.Classification option = new IntentClassifier.Classification(
                    intentId, "人事制度", "员工制度", "按制度说明适用范围", 0.8, List.of("员工"));
            when(fixture.jdbc.queryForObject(contains("SELECT count(*) FROM conversation WHERE id"), eq(Integer.class), any(Object[].class)))
                    .thenReturn(1);
            doAnswer(invocation -> {
                RowMapper<UUID> rowMapper = invocation.getArgument(1);
                ResultSet resultSet = mock(ResultSet.class);
                when(resultSet.getObject(1, UUID.class)).thenReturn(KB_ID);
                return List.of(rowMapper.mapRow(resultSet, 0));
            }).when(fixture.jdbc).query(contains("SELECT knowledge_base_id FROM conversation"), any(RowMapper.class), any(Object[].class));
            doAnswer(invocation -> {
                RowMapper<Object> rowMapper = invocation.getArgument(1);
                ResultSet resultSet = mock(ResultSet.class);
                when(resultSet.getObject("knowledge_base_id", UUID.class)).thenReturn(KB_ID);
                when(resultSet.getString("pending_question")).thenReturn("员工有哪些请假规定？");
                when(resultSet.getString("pending_intents")).thenReturn(fixture.mapper.writeValueAsString(List.of(option)));
                return List.of(rowMapper.mapRow(resultSet, 0));
            }).when(fixture.jdbc).query(contains("pending_question"), any(RowMapper.class), any(Object[].class));
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30)))
                    .thenReturn(List.of(vectorHit("clarified", "员工请假制度规定说明", 0.9)));

            fixture.service.stream(USER_ID, false,
                    new RagChatController.ChatRequest(KB_ID, conversationId, "人事制度", intentId),
                    new SseEmitter(30_000L), new AtomicBoolean());

            verify(fixture.vector).search(eq(KB_ID), contains("员工有哪些请假规定"), eq(30));
            verify(fixture.vector).search(eq(KB_ID), contains("用户澄清方向：人事制度"), eq(30));
            verify(fixture.modelRouter).stream(contains("人事制度"), anyString(), any(), any());
            verify(fixture.jdbc).update(contains("pending_question=NULL"), eq(conversationId), eq(USER_ID));
        }
    }

    @Test
    void rejectsLowSimilarityVectorNeighborsAsEvidence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30)))
                    .thenReturn(List.of(vectorHit("weak", "unrelated passage", 0.08)));
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30))).thenReturn(List.of());

            fixture.stream();

            verify(fixture.modelRouter, never()).stream(anyString(), anyString(), any(), any());
            assertThat(fixture.assistantMessages().get(0)[2].toString())
                    .contains("没有在这个知识库中找到足够的资料");
            assertThat(fixture.traceRetrieval().path("citations").asInt()).isZero();
        }
    }

    @Test
    void mergesDuplicateHitsUsingReciprocalRankFusion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<RagChatService.Citation> citations = fixture.service.fuse("policy keyword evidence", List.of(
                            vectorHit("shared", "shared evidence"), vectorHit("vector-only", "vector evidence")), List.of(
                            keywordHit("keyword-only", "keyword evidence"), keywordHit("shared", "shared evidence")));

            assertThat(citations).extracting(RagChatService.Citation::chunkId)
                    .containsExactly("shared", "keyword-only", "vector-only");
            assertThat(citations).filteredOn(citation -> citation.chunkId().equals("shared")).hasSize(1);
        }
    }

    @Test
    void decomposesCompoundQuestionAndRetrievesEachSubQuestionSeparately() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String firstQuestion = "员工请假怎么申请？";
            String secondQuestion = "财务报销需要什么材料？";
            when(fixture.intents.classifyBest(firstQuestion)).thenReturn(new IntentClassifier.Classification(
                    "intent-hr", "人事制度", "员工制度", "说明适用范围", 0.9, List.of("请假")));
            when(fixture.intents.classifyBest(secondQuestion)).thenReturn(new IntentClassifier.Classification(
                    "intent-finance", "财务流程", "报销流程", "说明材料和审批", 0.9, List.of("报销")));
            when(fixture.vector.search(eq(KB_ID), contains("员工请假"), eq(30)))
                    .thenReturn(List.of(vectorHit("leave", "员工请假按流程提交申请", 0.92)));
            when(fixture.vector.search(eq(KB_ID), contains("财务报销"), eq(30)))
                    .thenReturn(List.of(vectorHit("expense", "财务报销需要发票和审批单", 0.91)));
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), contains("员工请假"), eq(false), eq(30)))
                    .thenReturn(List.of(keywordHit("leave", "员工请假按流程提交申请")));
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), contains("财务报销"), eq(false), eq(30)))
                    .thenReturn(List.of(keywordHit("expense", "财务报销需要发票和审批单")));

            fixture.stream(firstQuestion + secondQuestion);

            verify(fixture.vector).search(eq(KB_ID), contains("员工请假"), eq(30));
            verify(fixture.vector).search(eq(KB_ID), contains("财务报销"), eq(30));
            verify(fixture.keyword).search(eq(KB_ID), eq(USER_ID), contains("员工请假"), eq(false), eq(30));
            verify(fixture.keyword).search(eq(KB_ID), eq(USER_ID), contains("财务报销"), eq(false), eq(30));
            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(fixture.modelRouter).stream(anyString(), prompt.capture(), any(), any());
            assertThat(prompt.getValue()).contains("按编号逐项作答", firstQuestion, secondQuestion,
                    "员工请假按流程提交申请", "财务报销需要发票和审批单");
            JsonNode trace = fixture.traceRetrieval();
            List<String> tracedQuestions = new ArrayList<>();
            trace.path("subQuestions").forEach(node -> tracedQuestions.add(node.asText()));
            assertThat(tracedQuestions).containsExactly(firstQuestion, secondQuestion);
            assertThat(trace.path("intentRoutes")).hasSize(2);
            assertThat(trace.path("intentId").asText()).contains("intent-hr", "intent-finance");
        }
    }

    @Test
    void directsIntentSearchToMappedKnowledgeBaseAndRecordsTheTarget() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID mappedKnowledgeBaseId = UUID.randomUUID();
            IntentClassifier.Classification intent = new IntentClassifier.Classification(
                    "intent-hr", "人事制度", "员工制度", "说明适用范围", 0.9, List.of("请假"), List.of(mappedKnowledgeBaseId));
            when(fixture.intents.analyze(anyString())).thenReturn(new IntentClassifier.Decision(intent, List.of(), false));
            doAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                assertThat(sql).contains("AND owner_id=?");
                return List.of(mappedKnowledgeBaseId);
            }).when(fixture.jdbc).query(contains("SELECT id FROM knowledge_base WHERE id IN"), any(RowMapper.class), any(Object[].class));
            when(fixture.vector.search(eq(mappedKnowledgeBaseId), anyString(), eq(30)))
                    .thenReturn(List.of(vectorHit("directed", "人事知识库中的请假制度", 0.95)));
            when(fixture.keyword.search(eq(mappedKnowledgeBaseId), eq(USER_ID), anyString(), eq(false), eq(30)))
                    .thenReturn(List.of());

            fixture.stream("员工请假怎么申请？");

            verify(fixture.vector).search(eq(mappedKnowledgeBaseId), anyString(), eq(30));
            verify(fixture.keyword).search(eq(mappedKnowledgeBaseId), eq(USER_ID), anyString(), eq(false), eq(30));
            verify(fixture.vector, never()).search(eq(KB_ID), anyString(), eq(30));
            assertThat(fixture.traceRetrieval().path("intentRoutes").get(0).path("knowledgeBaseIds").get(0).asText())
                    .isEqualTo(mappedKnowledgeBaseId.toString());
            assertThat(fixture.assistantMessages().get(0)[3].toString()).contains(mappedKnowledgeBaseId.toString());
        }
    }

    @Test
    void splitsCompoundQuestionAndDirectsEachIntentToItsOwnKnowledgeBase() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID hrKnowledgeBaseId = UUID.randomUUID();
            UUID financeKnowledgeBaseId = UUID.randomUUID();
            String hrQuestion = "员工请假怎么申请？";
            String financeQuestion = "财务报销需要什么材料？";
            when(fixture.intents.classifyBest(hrQuestion)).thenReturn(new IntentClassifier.Classification(
                    "intent-hr", "人事制度", "员工制度", "说明适用范围", 0.9, List.of("请假"), List.of(hrKnowledgeBaseId)));
            when(fixture.intents.classifyBest(financeQuestion)).thenReturn(new IntentClassifier.Classification(
                    "intent-finance", "财务流程", "报销流程", "说明所需材料", 0.9, List.of("报销"), List.of(financeKnowledgeBaseId)));
            doAnswer(invocation -> {
                UUID requestedId = invocation.getArgument(2);
                assertThat((String) invocation.getArgument(0)).contains("AND owner_id=?");
                return requestedId.equals(hrKnowledgeBaseId) || requestedId.equals(financeKnowledgeBaseId)
                        ? List.of(requestedId) : List.of();
            }).when(fixture.jdbc).query(contains("SELECT id FROM knowledge_base WHERE id IN"), any(RowMapper.class), any(Object[].class));
            when(fixture.vector.search(eq(hrKnowledgeBaseId), anyString(), eq(30)))
                    .thenReturn(List.of(vectorHit("hr-result", "员工请假应提交审批申请", 0.95)));
            when(fixture.vector.search(eq(financeKnowledgeBaseId), anyString(), eq(30)))
                    .thenReturn(List.of(vectorHit("finance-result", "报销需要发票和审批单", 0.95)));
            when(fixture.keyword.search(eq(hrKnowledgeBaseId), eq(USER_ID), anyString(), eq(false), eq(30))).thenReturn(List.of());
            when(fixture.keyword.search(eq(financeKnowledgeBaseId), eq(USER_ID), anyString(), eq(false), eq(30))).thenReturn(List.of());

            fixture.stream(hrQuestion + financeQuestion);

            ArgumentCaptor<Object[]> traceArguments = ArgumentCaptor.forClass(Object[].class);
            verify(fixture.jdbc).update(contains("INSERT INTO rag_trace"), traceArguments.capture());
            assertThat(traceArguments.getValue()[8]).as("RAG routing error").isNull();
            verify(fixture.vector).search(eq(hrKnowledgeBaseId), contains("员工请假"), eq(30));
            verify(fixture.vector).search(eq(financeKnowledgeBaseId), contains("财务报销"), eq(30));
            verify(fixture.vector, never()).search(eq(KB_ID), anyString(), eq(30));
            JsonNode routes = fixture.traceRetrieval().path("intentRoutes");
            assertThat(routes).hasSize(2);
            assertThat(routes.get(0).path("knowledgeBaseIds").get(0).asText()).isEqualTo(hrKnowledgeBaseId.toString());
            assertThat(routes.get(1).path("knowledgeBaseIds").get(0).asText()).isEqualTo(financeKnowledgeBaseId.toString());
            assertThat(fixture.assistantMessages().get(0)[3].toString())
                    .contains(hrKnowledgeBaseId.toString(), financeKnowledgeBaseId.toString());
        }
    }

    @Test
    void ambiguousSubQuestionUsesGenericSelectedKnowledgeBaseRetrieval() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String firstQuestion = "系统怎么处理？";
            String secondQuestion = "制度应该如何执行？";
            when(fixture.intents.classifyBest(firstQuestion)).thenReturn(null);
            when(fixture.intents.classifyBest(secondQuestion)).thenReturn(null);
            when(fixture.vector.search(eq(KB_ID), contains("系统怎么处理"), eq(30))).thenReturn(List.of());
            when(fixture.vector.search(eq(KB_ID), contains("制度应该如何执行"), eq(30))).thenReturn(List.of());
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), contains("系统怎么处理"), eq(false), eq(30))).thenReturn(List.of());
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), contains("制度应该如何执行"), eq(false), eq(30))).thenReturn(List.of());

            fixture.stream(firstQuestion + secondQuestion);

            verify(fixture.vector).search(eq(KB_ID), contains("系统怎么处理"), eq(30));
            verify(fixture.vector).search(eq(KB_ID), contains("制度应该如何执行"), eq(30));
            verify(fixture.vector, times(2)).search(eq(KB_ID), anyString(), eq(30));
            JsonNode routes = fixture.traceRetrieval().path("intentRoutes");
            assertThat(routes).hasSize(2);
            for (JsonNode route : routes) {
                assertThat(route.path("intentId").asText()).isEmpty();
                assertThat(route.path("knowledgeBaseIds").get(0).asText()).isEqualTo(KB_ID.toString());
            }
        }
    }

    @Test
    void inaccessibleIntentMappingFallsBackToTheAuthorizedSelectedKnowledgeBase() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID inaccessibleKnowledgeBaseId = UUID.randomUUID();
            IntentClassifier.Classification intent = new IntentClassifier.Classification(
                    "intent-hr", "人事制度", "员工制度", "说明适用范围", 0.9, List.of("请假"), List.of(inaccessibleKnowledgeBaseId));
            when(fixture.intents.analyze(anyString())).thenReturn(new IntentClassifier.Decision(intent, List.of(), false));
            doAnswer(invocation -> {
                assertThat((String) invocation.getArgument(0)).contains("AND owner_id=?");
                return List.of();
            }).when(fixture.jdbc).query(contains("SELECT id FROM knowledge_base WHERE id IN"), any(RowMapper.class), any(Object[].class));
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30))).thenReturn(List.of());
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30))).thenReturn(List.of());

            fixture.stream("员工请假怎么申请？");

            verify(fixture.vector).search(eq(KB_ID), anyString(), eq(30));
            verify(fixture.vector, never()).search(eq(inaccessibleKnowledgeBaseId), anyString(), eq(30));
            assertThat(fixture.traceRetrieval().path("intentRoutes").get(0).path("knowledgeBaseIds").get(0).asText())
                    .isEqualTo(KB_ID.toString());
        }
    }

    @Test
    void usesConfiguredRerankerOrder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.rerank.configured()).thenReturn(true);
            when(fixture.rerank.rerank(eq("policy"), any(), eq(6)))
                    .thenReturn(List.of(new RerankService.RankedDocument(1, 0.99), new RerankService.RankedDocument(0, 0.75)));

            List<RagChatService.Citation> citations = fixture.service.fuse("policy", List.of(
                    vectorHit("first", "first candidate"), vectorHit("second", "second candidate")), List.of());

            assertThat(citations).extracting(RagChatService.Citation::chunkId).containsExactly("second", "first");
        }
    }

    @Test
    void preservesRrfOrderWhenRerankerFails() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.rerank.configured()).thenReturn(true);
            when(fixture.rerank.rerank(eq("policy"), any(), eq(6))).thenThrow(new IOException("rerank offline"));

            List<RagChatService.Citation> citations = fixture.service.fuse("policy", List.of(
                    vectorHit("first", "first candidate"), vectorHit("second", "second candidate")), List.of());

            assertThat(citations).extracting(RagChatService.Citation::chunkId).containsExactly("first", "second");
        }
    }

    @Test
    void recordsRerankFallbackDurationAndErrorInTrace() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.vector.search(eq(KB_ID), anyString(), eq(30))).thenReturn(List.of(
                    vectorHit("first", "policy evidence one"), vectorHit("second", "policy evidence two")));
            when(fixture.keyword.search(eq(KB_ID), eq(USER_ID), anyString(), eq(false), eq(30))).thenReturn(List.of());
            when(fixture.rerank.configured()).thenReturn(true);
            when(fixture.rerank.rerank(anyString(), any(), eq(6))).thenThrow(new IOException("rerank offline"));

            fixture.stream("policy evidence question");

            JsonNode rerank = findStage(fixture.traceRetrieval().path("stages"), "rerank");
            assertThat(rerank.path("status").asText()).isEqualTo("FALLBACK");
            assertThat(rerank.path("error").asText()).contains("IOException: rerank offline");
            assertThat(rerank.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        }
    }

    private static VectorRetriever.SearchHit vectorHit(String id, String content) {
        return vectorHit(id, content, 0.9);
    }

    private static VectorRetriever.SearchHit vectorHit(String id, String content, double score) {
        return new VectorRetriever.SearchHit(id, "doc-" + id, "vector.txt", 0, content, score, "vector");
    }

    private static ElasticsearchChunkIndex.KeywordHit keywordHit(String id, String content) {
        return new ElasticsearchChunkIndex.KeywordHit(id, "doc-" + id, "keyword.txt", content, 1.0);
    }

    private static JsonNode findStage(JsonNode stages, String name) {
        for (JsonNode stage : stages) if (name.equals(stage.path("name").asText())) return stage;
        throw new AssertionError("Trace stage not found: " + name);
    }

    private static final class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final VectorRetriever vector = mock(VectorRetriever.class);
        final ElasticsearchChunkIndex keyword = mock(ElasticsearchChunkIndex.class);
        final ChatModelRouter modelRouter = mock(ChatModelRouter.class);
        final FairQueueLimiter limiter = mock(FairQueueLimiter.class);
        final FairQueueLimiter.Lease lease = mock(FairQueueLimiter.Lease.class);
        final McpToolClient mcp = mock(McpToolClient.class);
        final IntentClassifier intents = mock(IntentClassifier.class);
        final ConversationMemory memory = new ConversationMemory(jdbc);
        final RerankService rerank = mock(RerankService.class);
        final ObjectMapper mapper = new ObjectMapper();
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final RagChatService service;

        Fixture() throws Exception {
            doAnswer(invocation -> List.of()).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
            when(jdbc.queryForObject(contains("SELECT count(*) FROM knowledge_base"), eq(Integer.class), any(Object[].class))).thenReturn(1);
            when(jdbc.queryForObject(contains("SELECT count(*) FROM conversation_message"), eq(Integer.class), any(Object[].class))).thenReturn(0);
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
            when(limiter.acquire(eq(USER_ID), any(AtomicBoolean.class), any(IntConsumer.class))).thenReturn(lease);
            when(intents.analyze(anyString())).thenReturn(new IntentClassifier.Decision(
                    new IntentClassifier.Classification("intent-1", "常规咨询", "", "", 0.9, List.of("问题")), List.of(), false));
            when(mcp.invokeForQuestion(anyString())).thenReturn("");
            when(modelRouter.stream(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
                Consumer<String> onToken = invocation.getArgument(2);
                Consumer<ChatModelRouter.ModelAttempt> onAttempt = invocation.getArgument(3);
                onToken.accept("evidence-backed answer");
                onAttempt.accept(new ChatModelRouter.ModelAttempt("test-model", "test-model", "SUCCESS", 1, null, true));
                return "test-model";
            });
            service = new RagChatService(jdbc, vector, keyword, modelRouter, limiter, mapper, executor, mcp, intents, memory, rerank,
                    new QueryTermMappingService(jdbc, mapper), new MultiQuestionRewriteService(modelRouter, mapper), 0.25, 0.1);
        }

        void stream() {
            stream("policy evidence question");
        }

        void stream(String question) {
            service.stream(USER_ID, false, new RagChatController.ChatRequest(KB_ID, null, question),
                    new SseEmitter(30_000L), new AtomicBoolean());
        }

        JsonNode traceRetrieval() throws Exception {
            ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).update(contains("INSERT INTO rag_trace"), arguments.capture());
            return mapper.readTree(arguments.getValue()[4].toString());
        }

        List<Object[]> assistantMessages() {
            ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc, times(2)).update(contains("INSERT INTO conversation_message"), arguments.capture());
            List<Object[]> assistant = new ArrayList<>();
            for (Object[] params : arguments.getAllValues()) if (params.length == 5) assistant.add(params);
            return assistant;
        }

        @Override public void close() { executor.shutdownNow(); }
    }
}
