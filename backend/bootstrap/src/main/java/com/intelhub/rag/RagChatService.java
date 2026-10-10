package com.intelhub.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.intelhub.ai.ChatModelRouter;
import com.intelhub.ai.RerankService;
import com.intelhub.knowledge.ElasticsearchChunkIndex;
import com.intelhub.mcp.McpToolClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RagChatService {
    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);
    private final JdbcTemplate jdbc;
    private final VectorRetriever vectorRetriever;
    private final ElasticsearchChunkIndex keywordIndex;
    private final ChatModelRouter modelRouter;
    private final FairQueueLimiter limiter;
    private final ObjectMapper mapper;
    private final Executor searchExecutor;
    private final McpToolClient mcpToolClient;
    private final IntentClassifier intentClassifier;
    private final ConversationMemory conversationMemory;
    private final RerankService rerankService;
    private final QueryTermMappingService queryTermMappingService;
    private final MultiQuestionRewriteService multiQuestionRewriteService;
    private final double minimumVectorSimilarity;
    private final double minimumLexicalOverlap;

    public RagChatService(JdbcTemplate jdbc, VectorRetriever vectorRetriever, ElasticsearchChunkIndex keywordIndex,
                          ChatModelRouter modelRouter, FairQueueLimiter limiter, ObjectMapper mapper,
                          @Qualifier("searchExecutor") Executor searchExecutor, McpToolClient mcpToolClient,
                          IntentClassifier intentClassifier, ConversationMemory conversationMemory, RerankService rerankService,
                          QueryTermMappingService queryTermMappingService, MultiQuestionRewriteService multiQuestionRewriteService,
                          @Value("${intelhub.rag.minimum-vector-similarity:0.25}") double minimumVectorSimilarity,
                          @Value("${intelhub.rag.minimum-lexical-overlap:0.1}") double minimumLexicalOverlap) {
        this.jdbc = jdbc;
        this.vectorRetriever = vectorRetriever;
        this.keywordIndex = keywordIndex;
        this.modelRouter = modelRouter;
        this.limiter = limiter;
        this.mapper = mapper;
        this.searchExecutor = searchExecutor;
        this.mcpToolClient = mcpToolClient;
        this.intentClassifier = intentClassifier;
        this.conversationMemory = conversationMemory;
        this.rerankService = rerankService;
        this.queryTermMappingService = queryTermMappingService;
        this.multiQuestionRewriteService = multiQuestionRewriteService;
        if (minimumVectorSimilarity < 0 || minimumVectorSimilarity > 1 || minimumLexicalOverlap < 0 || minimumLexicalOverlap > 1) {
            throw new IllegalArgumentException("RAG 证据阈值必须在 0 到 1 之间");
        }
        this.minimumVectorSimilarity = minimumVectorSimilarity;
        this.minimumLexicalOverlap = minimumLexicalOverlap;
    }

    public void stream(UUID userId, boolean admin, RagChatController.ChatRequest request, SseEmitter emitter, AtomicBoolean cancelled) {
        long started = System.currentTimeMillis();
        UUID traceId = UUID.randomUUID();
        UUID conversationId = request.conversationId();
        String modelId = "";
        String answer = "";
        String traceQuestion = request.question();
        String contextualQuery = request.question();
        String retrievalQuery = request.question();
        String memorySummary = "";
        String intentId = "";
        List<String> subQuestions = List.of();
        List<SubQuestionRoute> questionRoutes = List.of();
        List<Citation> citations = List.of();
        List<Map<String, Object>> stages = new ArrayList<>();
        String toolResult = "";
        int vectorCount = 0;
        int keywordCount = 0;
        boolean vectorAvailable = false;
        boolean keywordAvailable = false;
        String vectorError = null;
        String keywordError = null;
        String state = "FAILED";
        String error = null;
        try (FairQueueLimiter.Lease ignored = limiter.acquire(userId, cancelled, position -> send(emitter, "status", Map.of("stage", "queued", "position", position)))) {
            requireKnowledgeBaseAccess(request.knowledgeBaseId(), userId, admin);
            if (request.clarificationAnswer() != null && request.conversationId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "澄清回答必须关联原会话");
            }
            conversationId = ensureConversation(userId, request.knowledgeBaseId(), conversationId, request.question());
            UUID activeConversationId = conversationId;
            send(emitter, "status", Map.of("stage", "rewriting", "message", "正在理解问题"));

            IntentClassifier.Classification intent;
            if (request.clarificationAnswer() != null) {
                PendingClarification pending = loadPendingClarification(conversationId, userId);
                IntentClassifier.Classification selected = pending.options().stream()
                        .filter(option -> option.id().equals(request.clarificationAnswer())).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "澄清选项无效或已过期"));
                if (!pending.knowledgeBaseId().equals(request.knowledgeBaseId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "澄清回答必须使用原知识库");
                }
                Map<String, Object> selectedIntentDetails = new LinkedHashMap<>();
                selectedIntentDetails.put("source", "user_clarification");
                selectedIntentDetails.put("intentId", selected.id());
                selectedIntentDetails.put("intentName", selected.name());
                recordStage(stages, "intent_classification", 0, "SUCCESS", null, selectedIntentDetails);
                ConversationMemory.Context context = measureStage(stages, "context_rewrite", Map.of("source", "clarification_context"),
                        () -> conversationMemory.loadAndRewrite(activeConversationId, pending.question()));
                contextualQuery = context.rewrittenQuestion();
                memorySummary = context.summary();
                if (!"__general__".equals(selected.id())) {
                    contextualQuery += "\n用户澄清方向：" + selected.name();
                    intent = selected;
                } else {
                    intent = null;
                }
                subQuestions = List.of(contextualQuery);
                questionRoutes = List.of(new SubQuestionRoute(contextualQuery, intent));
                traceQuestion = pending.question() + "（澄清方向：" + selected.name() + "）";
                jdbc.update("INSERT INTO conversation_message(id,conversation_id,role,content) VALUES (?,?, 'user', ?)",
                        UUID.randomUUID(), conversationId, selected.name());
                int cleared = jdbc.update("UPDATE conversation SET pending_question=NULL,pending_intents='[]'::jsonb WHERE id=? AND owner_id=? AND pending_question IS NOT NULL",
                        conversationId, userId);
                if (cleared != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "澄清状态已被处理，请重新提问");
            } else {
                ConversationMemory.Context context = measureStage(stages, "context_rewrite", Map.of("source", "conversation_memory"),
                        () -> conversationMemory.loadAndRewrite(activeConversationId, request.question()));
                contextualQuery = context.rewrittenQuestion();
                memorySummary = context.summary();
                jdbc.update("INSERT INTO conversation_message(id,conversation_id,role,content) VALUES (?,?, 'user', ?)",
                        UUID.randomUUID(), conversationId, request.question());
                String queryToRewrite = contextualQuery;
                long rewriteStarted = System.nanoTime();
                MultiQuestionRewriteService.RewriteResult rewrite;
                try {
                    rewrite = multiQuestionRewriteService.rewrite(queryToRewrite);
                    Map<String, Object> rewriteDetails = new LinkedHashMap<>();
                    rewriteDetails.put("strategy", rewrite.strategy());
                    rewriteDetails.put("rewrittenQuery", rewrite.rewrittenQuestion());
                    rewriteDetails.put("subQuestions", rewrite.subQuestions());
                    rewriteDetails.put("fallback", rewrite.error() != null);
                    recordStage(stages, "query_rewrite", elapsedMs(rewriteStarted),
                            rewrite.error() == null ? "SUCCESS" : "FALLBACK", rewrite.error(), rewriteDetails);
                } catch (Exception exception) {
                    recordStage(stages, "query_rewrite", elapsedMs(rewriteStarted), "FAILED", safeError(exception), Map.of());
                    throw exception;
                }
                contextualQuery = rewrite.rewrittenQuestion();
                subQuestions = rewrite.subQuestions().isEmpty() ? List.of(contextualQuery) : rewrite.subQuestions();
                send(emitter, "rewrite", Map.of("rewrittenQuestion", contextualQuery, "subQuestions", subQuestions));

                if (subQuestions.size() == 1) {
                    String questionForIntent = contextualQuery;
                    IntentClassifier.Decision decision = measureStage(stages, "intent_classification",
                            Map.of("questions", List.of(questionForIntent)), () -> intentClassifier.analyze(questionForIntent));
                    if (decision.needsClarification()) {
                        String clarificationText = "为了更准确地回答，请选择最接近的咨询方向；如果不限定方向，可以选通用检索。";
                        jdbc.update("UPDATE conversation SET pending_question=?,pending_intents=?::jsonb,updated_at=now() WHERE id=? AND owner_id=?",
                                request.question(), mapper.writeValueAsString(decision.options()), conversationId, userId);
                        jdbc.update("INSERT INTO conversation_message(id,conversation_id,role,content,model_id) VALUES (?,?, 'assistant', ?,?)",
                                UUID.randomUUID(), conversationId, clarificationText, "clarification");
                        jdbc.update("UPDATE conversation SET updated_at=now() WHERE id=?", conversationId);
                        conversationMemory.refreshSummary(conversationId);
                        state = "NEEDS_CLARIFICATION";
                        answer = clarificationText;
                        modelId = "clarification";
                        Map<String, Object> clarification = new LinkedHashMap<>();
                        clarification.put("message", clarificationText);
                        clarification.put("conversationId", conversationId.toString());
                        clarification.put("options", decision.options().stream().map(option -> Map.of(
                                "id", option.id(), "name", option.name(), "description", option.description() == null ? "" : option.description())).toList());
                        send(emitter, "clarification", clarification);
                        send(emitter, "done", Map.of("conversationId", conversationId.toString(), "traceId", traceId.toString(),
                                "modelId", modelId, "needsClarification", true));
                        return;
                    }
                    intent = decision.classification();
                    questionRoutes = List.of(new SubQuestionRoute(contextualQuery, intent));
                } else {
                    List<String> questionsForIntent = subQuestions;
                    List<IntentClassifier.Classification> classifications = measureStage(stages, "intent_classification",
                            Map.of("questions", questionsForIntent),
                            () -> questionsForIntent.stream().map(intentClassifier::classifyBest).toList());
                    List<SubQuestionRoute> routes = new ArrayList<>();
                    for (int i = 0; i < questionsForIntent.size(); i++) {
                        routes.add(new SubQuestionRoute(questionsForIntent.get(i), classifications.get(i)));
                    }
                    questionRoutes = List.copyOf(routes);
                    intent = null;
                }
            }
            long intentRoutingStarted = System.nanoTime();
            try {
                questionRoutes = questionRoutes.stream()
                        .map(route -> route.withKnowledgeBases(resolveKnowledgeBases(route.intent(), request.knowledgeBaseId(), userId, admin)))
                        .toList();
                recordStage(stages, "intent_routing", elapsedMs(intentRoutingStarted), "SUCCESS", null,
                        Map.of("routes", intentRouteTrace(questionRoutes)));
            } catch (Exception exception) {
                recordStage(stages, "intent_routing", elapsedMs(intentRoutingStarted), "FAILED", safeError(exception), Map.of());
                throw exception;
            }
            List<String> selectedIntentIds = questionRoutes.stream().map(SubQuestionRoute::intent).filter(java.util.Objects::nonNull)
                    .map(IntentClassifier.Classification::id).distinct().toList();
            intentId = String.join(",", selectedIntentIds);
            int routeCount = questionRoutes.size();
            for (int i = 0; i < questionRoutes.size(); i++) {
                SubQuestionRoute route = questionRoutes.get(i);
                if (route.intent() == null) continue;
                Map<String, Object> intentEvent = new LinkedHashMap<>();
                intentEvent.put("name", route.intent().name());
                intentEvent.put("confidence", route.intent().confidence());
                intentEvent.put("matchedKeywords", route.intent().matchedKeywords());
                intentEvent.put("knowledgeBaseIds", route.knowledgeBaseIds().stream().map(UUID::toString).toList());
                if (questionRoutes.size() > 1) {
                    intentEvent.put("subQuestion", route.question());
                    intentEvent.put("index", i + 1);
                }
                send(emitter, "intent", intentEvent);
            }
            send(emitter, "status", Map.of("stage", "retrieving", "message", "正在并行检索知识"));
            List<CompletableFuture<QuestionRetrieval>> retrievalFutures = questionRoutes.stream()
                    .flatMap(route -> route.knowledgeBaseIds().stream().map(kb -> {
                        String vectorQuery = route.question();
                        String keywordQuery = queryTermMappingService.expand(route.question());
                        CompletableFuture<RetrievalResult<VectorRetriever.SearchHit>> vectorFuture = CompletableFuture.supplyAsync(
                                () -> retrieve("vector", () -> vectorRetriever.search(kb, vectorQuery, 30), traceId), searchExecutor);
                        CompletableFuture<RetrievalResult<ElasticsearchChunkIndex.KeywordHit>> keywordFuture = CompletableFuture.supplyAsync(
                                () -> retrieve("keyword", () -> keywordIndex.search(kb, userId, keywordQuery, admin, 30), traceId), searchExecutor);
                        return vectorFuture.thenCombine(keywordFuture, (vector, keyword) -> new QuestionRetrieval(route, kb, vector, keyword));
                    })).toList();
            List<QuestionRetrieval> questionResults = retrievalFutures.stream().map(CompletableFuture::join).toList();
            for (QuestionRetrieval result : questionResults) {
                Map<String, Object> vectorDetails = retrievalStageDetails(result, "vector", result.vector().hits().size());
                recordStage(stages, "vector_retrieval", result.vector().durationMs(),
                        result.vector().available() ? "SUCCESS" : "FAILED", result.vector().errorMessage(), vectorDetails);
                Map<String, Object> keywordDetails = retrievalStageDetails(result, "keyword", result.keyword().hits().size());
                recordStage(stages, "keyword_retrieval", result.keyword().durationMs(),
                        result.keyword().available() ? "SUCCESS" : "FAILED", result.keyword().errorMessage(), keywordDetails);
            }
            vectorAvailable = questionResults.stream().anyMatch(result -> result.vector().available());
            keywordAvailable = questionResults.stream().anyMatch(result -> result.keyword().available());
            vectorError = firstError(questionResults.stream().map(result -> result.vector().error()).toList());
            keywordError = firstError(questionResults.stream().map(result -> result.keyword().error()).toList());
            if (!vectorAvailable && !keywordAvailable) {
                throw new IllegalStateException("向量与关键词检索服务暂时不可用");
            }
            if (!vectorAvailable || !keywordAvailable || questionResults.stream().anyMatch(result ->
                    !result.vector().available() || !result.keyword().available())) {
                send(emitter, "status", Map.of("stage", "retrieval_fallback", "message", "部分检索源暂不可用，继续使用可用结果"));
            }
            vectorCount = questionResults.stream().mapToInt(result -> result.vector().hits().size()).sum();
            keywordCount = questionResults.stream().mapToInt(result -> result.keyword().hits().size()).sum();
            citations = fuseSubQuestions(questionResults, stages);
            retrievalQuery = questionRoutes.stream().map(route -> queryTermMappingService.expand(route.question()))
                    .distinct().collect(java.util.stream.Collectors.joining("；"));
            send(emitter, "citations", citations);
            String responseQuestion = buildSubQuestionPrompt(contextualQuery, subQuestions);
            toolResult = mcpToolClient.invokeForQuestion(responseQuestion);
            if (!toolResult.isBlank()) send(emitter, "status", Map.of("stage", "tool", "message", "已调用业务工具"));

            String intentHint = questionRoutes.stream().filter(route -> route.intent() != null)
                    .map(route -> (routeCount > 1 ? "子问题「" + route.question() + "」意图：" : "当前问题意图：")
                            + route.intent().name() + "。回答提示：" + (route.intent().promptHint() == null ? "" : route.intent().promptHint()) + "\n")
                    .collect(java.util.stream.Collectors.joining());
            String memoryHint = memorySummary.isBlank() ? "" : "会话记忆（只用于理解指代和上下文，不能作为事实依据）：\n" + memorySummary + "\n";
            String systemPrompt = "你是企业知识库问答助手。仅依据给定资料或 MCP 工具结果回答；两者都没有答案时明确说明未找到依据，不要编造。用中文作答，简洁清楚。\n" + memoryHint + intentHint;
            String prompt = buildPrompt(responseQuestion, citations, toolResult);
            if (citations.isEmpty() && toolResult.isBlank()) {
                answer = "我暂时没有在这个知识库中找到足够的资料来回答。请检查文档是否完成索引，或上传包含相关内容的文档。";
                sendText(emitter, answer);
                modelId = "no-evidence";
                recordStage(stages, "model_routing", 0, "SKIPPED", null,
                        Map.of("reason", "没有可用于回答的检索证据或工具结果"));
            } else {
                send(emitter, "status", Map.of("stage", "generating", "message", "正在生成回答"));
                StringBuilder generated = new StringBuilder();
                List<ChatModelRouter.ModelAttempt> modelAttempts = new ArrayList<>();
                long modelStarted = System.nanoTime();
                String modelStageStatus = "SUCCESS";
                String modelStageError = null;
                try {
                    modelId = modelRouter.stream(systemPrompt, prompt, token -> {
                        if (cancelled.get()) throw new IllegalStateException("客户端已断开");
                        generated.append(token);
                        send(emitter, "token", Map.of("text", token));
                    }, modelAttempts::add);
                } catch (Exception exception) {
                    modelStageStatus = "FAILED";
                    modelStageError = safeError(exception);
                    throw exception;
                } finally {
                    Map<String, Object> modelDetails = new LinkedHashMap<>();
                    modelDetails.put("selectedModel", modelId);
                    modelDetails.put("switched", modelAttempts.stream().anyMatch(attempt ->
                            "FAILED".equals(attempt.status()) || "SKIPPED_CIRCUIT".equals(attempt.status())));
                    modelDetails.put("attempts", modelAttempts);
                    recordStage(stages, "model_routing", elapsedMs(modelStarted), modelStageStatus, modelStageError, modelDetails);
                }
                answer = generated.toString();
            }
            UUID assistantMessageId = UUID.randomUUID();
            jdbc.update("INSERT INTO conversation_message(id,conversation_id,role,content,citations,model_id) VALUES (?,?, 'assistant', ?,?::jsonb,?)",
                    assistantMessageId, conversationId, answer, mapper.writeValueAsString(citations), modelId);
            jdbc.update("UPDATE conversation SET updated_at=now() WHERE id=?", conversationId);
            conversationMemory.refreshSummary(conversationId);
            state = "COMPLETED";
            send(emitter, "done", Map.of("conversationId", conversationId.toString(), "traceId", traceId.toString(),
                    "modelId", modelId, "assistantMessageId", assistantMessageId.toString()));
        } catch (Exception exception) {
            error = exception.getMessage() == null ? "问答请求失败" : exception.getMessage();
            send(emitter, "error", Map.of("message", error));
            if (conversationId != null && !answer.isBlank()) {
                jdbc.update("INSERT INTO conversation_message(id,conversation_id,role,content,model_id) VALUES (?,?, 'assistant', ?,?)",
                        UUID.randomUUID(), conversationId, answer, modelId);
            }
        } finally {
            try {
                Map<String, Object> retrievalTrace = new LinkedHashMap<>();
                retrievalTrace.put("vectorHits", vectorCount);
                retrievalTrace.put("keywordHits", keywordCount);
                retrievalTrace.put("citations", citations.size());
                retrievalTrace.put("vectorAvailable", vectorAvailable);
                retrievalTrace.put("keywordAvailable", keywordAvailable);
                if (vectorError != null) retrievalTrace.put("vectorError", vectorError);
                if (keywordError != null) retrievalTrace.put("keywordError", keywordError);
                retrievalTrace.put("mcpToolUsed", !toolResult.isBlank());
                retrievalTrace.put("rewrittenQuery", contextualQuery);
                retrievalTrace.put("retrievalQuery", retrievalQuery);
                retrievalTrace.put("subQuestions", subQuestions);
                retrievalTrace.put("intentRoutes", intentRouteTrace(questionRoutes));
                retrievalTrace.put("memoryUsed", !memorySummary.isBlank());
                retrievalTrace.put("intentId", intentId);
                retrievalTrace.put("clarificationRequired", "NEEDS_CLARIFICATION".equals(state));
                retrievalTrace.put("stages", stages);
                jdbc.update("INSERT INTO rag_trace(id,owner_id,conversation_id,query_text,retrieval,model_id,status,duration_ms,error_message) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",
                        traceId, userId, conversationId, traceQuestion, mapper.writeValueAsString(retrievalTrace),
                        modelId, state, System.currentTimeMillis() - started, error);
            } catch (Exception ignored) { }
            emitter.complete();
        }
    }

    private <T> T measureStage(List<Map<String, Object>> stages, String name, Map<String, Object> details,
                               CheckedSupplier<T> operation) throws Exception {
        long started = System.nanoTime();
        try {
            T result = operation.get();
            recordStage(stages, name, elapsedMs(started), "SUCCESS", null, details);
            return result;
        } catch (Exception exception) {
            recordStage(stages, name, elapsedMs(started), "FAILED", safeError(exception), details);
            throw exception;
        }
    }

    private static void recordStage(List<Map<String, Object>> stages, String name, long durationMs,
                                    String status, String error, Map<String, Object> details) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("name", name);
        stage.put("status", status);
        stage.put("durationMs", durationMs);
        if (error != null && !error.isBlank()) stage.put("error", error);
        if (details != null && !details.isEmpty()) stage.put("details", details);
        stages.add(stage);
    }

    private static Map<String, Object> retrievalStageDetails(QuestionRetrieval result, String channel, int hits) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("channel", channel);
        details.put("query", result.route().question());
        details.put("knowledgeBaseId", result.knowledgeBaseId().toString());
        details.put("intentId", result.route().intent() == null ? "" : result.route().intent().id());
        details.put("hitCount", hits);
        return details;
    }

    private static List<Map<String, Object>> intentRouteTrace(List<SubQuestionRoute> routes) {
        return routes.stream().map(route -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("question", route.question());
            details.put("intentId", route.intent() == null ? "" : route.intent().id());
            details.put("intentName", route.intent() == null ? "" : route.intent().name());
            details.put("confidence", route.intent() == null ? 0 : route.intent().confidence());
            details.put("matchedKeywords", route.intent() == null ? List.of() : route.intent().matchedKeywords());
            details.put("knowledgeBaseIds", route.knowledgeBaseIds().stream().map(UUID::toString).toList());
            return details;
        }).toList();
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        String result = exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return result.length() <= 500 ? result : result.substring(0, 500);
    }

    private <T> RetrievalResult<T> retrieve(String source, CheckedSupplier<List<T>> search, UUID traceId) {
        long started = System.nanoTime();
        try {
            return new RetrievalResult<>(List.copyOf(search.get()), null, null, elapsedMs(started));
        } catch (Exception unavailable) {
            log.warn("RAG {} retrieval failed for trace {}", source, traceId, unavailable);
            return new RetrievalResult<>(List.of(), unavailable.getClass().getSimpleName(), safeError(unavailable), elapsedMs(started));
        }
    }

    public List<Citation> retrieveForEvaluation(UUID knowledgeBaseId, UUID userId, boolean admin, String query) {
        UUID traceId = UUID.randomUUID();
        RetrievalResult<VectorRetriever.SearchHit> vectors = retrieve("vector", () -> vectorRetriever.search(knowledgeBaseId, query, 30), traceId);
        String keywordQuery = queryTermMappingService.expand(query);
        RetrievalResult<ElasticsearchChunkIndex.KeywordHit> keywords = retrieve("keyword",
                () -> keywordIndex.search(knowledgeBaseId, userId, keywordQuery, admin, 30), traceId);
        if (!vectors.available() && !keywords.available()) {
            throw new IllegalStateException("向量与关键词检索服务暂时不可用");
        }
        return fuse(query, vectors.hits(), keywords.hits(), false);
    }

    private UUID ensureConversation(UUID owner, UUID kb, UUID existing, String question) {
        if (existing != null) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM conversation WHERE id=? AND owner_id=?", Integer.class, existing, owner);
            if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
            List<UUID> knowledgeBases = jdbc.query("SELECT knowledge_base_id FROM conversation WHERE id=? AND owner_id=?",
                    (rs, row) -> rs.getObject(1, UUID.class), existing, owner);
            if (!knowledgeBases.isEmpty() && knowledgeBases.get(0) != null && !knowledgeBases.get(0).equals(kb)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "会话只能在创建时选择的知识库中继续");
            }
            if (knowledgeBases.isEmpty() || knowledgeBases.get(0) == null) {
                jdbc.update("UPDATE conversation SET knowledge_base_id=? WHERE id=? AND owner_id=?", kb, existing, owner);
            }
            return existing;
        }
        UUID id = UUID.randomUUID();
        String title = question.length() > 48 ? question.substring(0, 48) + "…" : question;
        jdbc.update("INSERT INTO conversation(id,owner_id,knowledge_base_id,title) VALUES (?,?,?,?)", id, owner, kb, title);
        return id;
    }

    private void requireKnowledgeBaseAccess(UUID kb, UUID userId, boolean admin) {
        int count = admin
                ? jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id=?", Integer.class, kb)
                : jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id=? AND owner_id=?", Integer.class, kb, userId);
        if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或无权访问");
    }

    private List<UUID> resolveKnowledgeBases(IntentClassifier.Classification intent, UUID selectedKnowledgeBaseId,
                                             UUID userId, boolean admin) {
        if (intent == null || intent.knowledgeBaseIds().isEmpty()) return List.of(selectedKnowledgeBaseId);
        List<UUID> configured = intent.knowledgeBaseIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (configured.isEmpty()) return List.of(selectedKnowledgeBaseId);
        String placeholders = String.join(",", java.util.Collections.nCopies(configured.size(), "?"));
        String sql = "SELECT id FROM knowledge_base WHERE id IN (" + placeholders + ")" + (admin ? "" : " AND owner_id=?");
        List<Object> arguments = new ArrayList<>(configured);
        if (!admin) arguments.add(userId);
        List<UUID> accessible = jdbc.query(sql, (rs, row) -> rs.getObject("id", UUID.class), arguments.toArray());
        // An inaccessible mapping must never widen a user's access. Fall back to the explicitly selected, already-authorized base.
        return accessible.isEmpty() ? List.of(selectedKnowledgeBaseId) : accessible.stream().distinct().toList();
    }

    private PendingClarification loadPendingClarification(UUID conversationId, UUID ownerId) {
        List<PendingClarification> rows = jdbc.query("SELECT knowledge_base_id,pending_question,pending_intents FROM conversation WHERE id=? AND owner_id=? AND pending_question IS NOT NULL",
                (rs, row) -> {
                    try {
                        return new PendingClarification(rs.getObject("knowledge_base_id", UUID.class), rs.getString("pending_question"),
                                mapper.readValue(rs.getString("pending_intents"), new TypeReference<List<IntentClassifier.Classification>>() {}));
                    } catch (Exception invalidState) {
                        throw new IllegalStateException("会话澄清状态无效", invalidState);
                    }
                },
                conversationId, ownerId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话没有待处理的澄清问题");
        return rows.get(0);
    }

    private record PendingClarification(UUID knowledgeBaseId, String question, List<IntentClassifier.Classification> options) {}

    private static String firstError(List<String> errors) {
        return errors.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private List<Citation> fuseSubQuestions(List<QuestionRetrieval> results, List<Map<String, Object>> stages) {
        Map<String, CitationAccumulator> merged = new LinkedHashMap<>();
        for (QuestionRetrieval result : results) {
            List<VectorRetriever.SearchHit> vectors = result.vector().available() ? result.vector().hits() : List.of();
            List<ElasticsearchChunkIndex.KeywordHit> keywords = result.keyword().available() ? result.keyword().hits() : List.of();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("query", result.route().question());
            details.put("knowledgeBaseId", result.knowledgeBaseId().toString());
            details.put("vectorHits", vectors.size());
            details.put("keywordHits", keywords.size());
            for (Citation citation : fuse(result.route().question(), vectors, keywords, true, stages, details)) {
                CitationAccumulator accumulator = merged.computeIfAbsent(citation.chunkId(), key -> new CitationAccumulator(
                        citation.chunkId(), citation.documentId(), citation.filename(), citation.chunkIndex(), citation.excerpt()));
                accumulator.rrf += citation.score();
                accumulator.knowledgeBaseId = result.knowledgeBaseId();
            }
        }
        int limit = Math.min(12, Math.max(6, results.size() * 3));
        return merged.values().stream().sorted(Comparator.comparingDouble((CitationAccumulator item) -> item.rrf).reversed())
                .limit(limit)
                .map(item -> new Citation(item.chunkId, item.documentId, item.filename, item.chunkIndex, item.content, item.rrf, item.knowledgeBaseId))
                .toList();
    }

    private static String buildSubQuestionPrompt(String originalQuestion, List<String> subQuestions) {
        if (subQuestions.size() <= 1) return originalQuestion;
        StringBuilder prompt = new StringBuilder("请分别回答以下相互独立的问题，按编号逐项作答；每项只使用能够支持该项的参考资料。\n");
        for (int index = 0; index < subQuestions.size(); index++) {
            prompt.append(index + 1).append(". ").append(subQuestions.get(index)).append("\n");
        }
        return prompt.append("原始问题：").append(originalQuestion).toString();
    }

    List<Citation> fuse(String query, List<VectorRetriever.SearchHit> vectors, List<ElasticsearchChunkIndex.KeywordHit> keywords) {
        return fuse(query, vectors, keywords, true);
    }

    private List<Citation> fuse(String query, List<VectorRetriever.SearchHit> vectors,
                                List<ElasticsearchChunkIndex.KeywordHit> keywords, boolean applyRerank) {
        return fuse(query, vectors, keywords, applyRerank, null, Map.of());
    }

    private List<Citation> fuse(String query, List<VectorRetriever.SearchHit> vectors,
                                List<ElasticsearchChunkIndex.KeywordHit> keywords, boolean applyRerank,
                                List<Map<String, Object>> stages, Map<String, Object> stageDetails) {
        long fusionStarted = System.nanoTime();
        Map<String, CitationAccumulator> merged = new LinkedHashMap<>();
        Set<String> terms = java.util.Arrays.stream(query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> !term.isBlank()).collect(java.util.stream.Collectors.toSet());
        for (int i = 0; i < vectors.size(); i++) {
            var hit = vectors.get(i);
            CitationAccumulator item = merged.computeIfAbsent(hit.chunkId(), key -> new CitationAccumulator(hit.chunkId(), hit.documentId(), hit.filename(), hit.chunkIndex(), hit.content()));
            item.rrf += 1.0 / (60 + i + 1);
            item.vectorSimilarity = Math.max(item.vectorSimilarity, hit.score());
            item.lexicalOverlap = Math.max(item.lexicalOverlap, lexicalOverlap(hit.content(), terms));
        }
        for (int i = 0; i < keywords.size(); i++) {
            var hit = keywords.get(i);
            CitationAccumulator item = merged.computeIfAbsent(hit.chunkId(), key -> new CitationAccumulator(hit.chunkId(), hit.documentId(), hit.filename(), -1, hit.content()));
            item.rrf += 1.0 / (60 + i + 1);
            item.lexicalOverlap = Math.max(item.lexicalOverlap, lexicalOverlap(hit.content(), terms));
        }
        Comparator<CitationAccumulator> fallbackOrder = Comparator
                .comparingDouble((CitationAccumulator c) -> c.rrf + c.lexicalOverlap * 0.001).reversed();
        List<CitationAccumulator> candidates = merged.values().stream()
                .filter(candidate -> candidate.vectorSimilarity >= minimumVectorSimilarity || candidate.lexicalOverlap >= minimumLexicalOverlap)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        candidates.sort(fallbackOrder);
        if (stages != null) {
            Map<String, Object> fusionDetails = new LinkedHashMap<>(stageDetails);
            fusionDetails.put("candidateCount", candidates.size());
            fusionDetails.put("rrfConstant", 60);
            recordStage(stages, "rrf_fusion", elapsedMs(fusionStarted), "SUCCESS", null, fusionDetails);
        }
        boolean rerankConfigured = applyRerank && rerankService.configured();
        if (rerankConfigured && candidates.size() > 1) {
            long rerankStarted = System.nanoTime();
            try {
                List<RerankService.RankedDocument> ranked = rerankService.rerank(query,
                        candidates.stream().map(candidate -> candidate.content).toList(), 6);
                List<CitationAccumulator> reranked = new ArrayList<>();
                Set<Integer> used = new java.util.HashSet<>();
                for (RerankService.RankedDocument result : ranked) {
                    if (result.index() >= 0 && result.index() < candidates.size() && used.add(result.index())) {
                        reranked.add(candidates.get(result.index()));
                    }
                }
                for (int i = 0; i < candidates.size(); i++) if (used.add(i)) reranked.add(candidates.get(i));
                candidates = reranked;
                if (stages != null) {
                    Map<String, Object> rerankDetails = new LinkedHashMap<>(stageDetails);
                    rerankDetails.put("candidateCount", candidates.size());
                    rerankDetails.put("fallback", false);
                    recordStage(stages, "rerank", elapsedMs(rerankStarted), "SUCCESS", null, rerankDetails);
                }
            } catch (Exception exception) {
                // Keep the deterministic RRF + lexical order when the optional provider is unavailable.
                if (stages != null) {
                    Map<String, Object> rerankDetails = new LinkedHashMap<>(stageDetails);
                    rerankDetails.put("candidateCount", candidates.size());
                    rerankDetails.put("fallback", true);
                    recordStage(stages, "rerank", elapsedMs(rerankStarted), "FALLBACK", safeError(exception), rerankDetails);
                }
            }
        } else if (stages != null) {
            Map<String, Object> rerankDetails = new LinkedHashMap<>(stageDetails);
            rerankDetails.put("candidateCount", candidates.size());
            rerankDetails.put("reason", !applyRerank ? "当前路径未启用重排" : !rerankConfigured ? "未配置重排模型" : "候选片段不足 2 条");
            recordStage(stages, "rerank", 0, "SKIPPED", null, rerankDetails);
        }
        return candidates.stream()
                .limit(6)
                .map(c -> new Citation(c.chunkId, c.documentId, c.filename, c.chunkIndex, c.content.length() > 500 ? c.content.substring(0, 500) + "…" : c.content, c.rrf, null))
                .toList();
    }

    private double lexicalOverlap(String content, Set<String> terms) {
        if (terms.isEmpty()) return 0;
        String value = content.toLowerCase(java.util.Locale.ROOT);
        long matched = terms.stream().filter(term -> !term.isBlank() && value.contains(term)).count();
        return (double) matched / terms.size();
    }

    private String buildPrompt(String query, List<Citation> citations, String toolResult) {
        StringBuilder prompt = new StringBuilder("问题：\n").append(query).append("\n\n参考资料：\n");
        for (int i = 0; i < citations.size(); i++) {
            Citation citation = citations.get(i);
            prompt.append("[资料 ").append(i + 1).append("] 文件：").append(citation.filename()).append("\n").append(citation.excerpt()).append("\n\n");
        }
        if (!toolResult.isBlank()) prompt.append("业务工具结果（模拟演示数据）：\n").append(toolResult).append("\n");
        return prompt.toString();
    }

    private void sendText(SseEmitter emitter, String text) {
        for (int i = 0; i < text.length(); i += 16) send(emitter, "token", Map.of("text", text.substring(i, Math.min(i + 16, text.length()))));
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try { emitter.send(SseEmitter.event().name(event).data(data)); }
        catch (Exception ex) { throw new IllegalStateException("无法发送问答事件", ex); }
    }

    public record Citation(String chunkId, String documentId, String filename, int chunkIndex, String excerpt, double score, UUID knowledgeBaseId) {
        public Citation(String chunkId, String documentId, String filename, int chunkIndex, String excerpt, double score) {
            this(chunkId, documentId, filename, chunkIndex, excerpt, score, null);
        }
    }
    private static final class CitationAccumulator {
        final String chunkId;
        final String documentId;
        final String filename;
        final int chunkIndex;
        final String content;
        UUID knowledgeBaseId;
        double rrf;
        double vectorSimilarity = Double.NEGATIVE_INFINITY;
        double lexicalOverlap;
        CitationAccumulator(String chunkId, String documentId, String filename, int chunkIndex, String content) {
            this.chunkId = chunkId; this.documentId = documentId; this.filename = filename; this.chunkIndex = chunkIndex; this.content = content;
        }
    }

    private record RetrievalResult<T>(List<T> hits, String error, String errorMessage, long durationMs) {
        boolean available() { return error == null; }
    }

    private record SubQuestionRoute(String question, IntentClassifier.Classification intent, List<UUID> knowledgeBaseIds) {
        SubQuestionRoute(String question, IntentClassifier.Classification intent) { this(question, intent, List.of()); }
        SubQuestionRoute { knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds); }
        SubQuestionRoute withKnowledgeBases(List<UUID> ids) { return new SubQuestionRoute(question, intent, ids); }
    }

    private record QuestionRetrieval(SubQuestionRoute route, UUID knowledgeBaseId, RetrievalResult<VectorRetriever.SearchHit> vector,
                                     RetrievalResult<ElasticsearchChunkIndex.KeywordHit> keyword) {}

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
