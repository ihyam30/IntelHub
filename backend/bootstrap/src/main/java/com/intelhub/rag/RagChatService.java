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
    private final double minimumVectorSimilarity;
    private final double minimumLexicalOverlap;

    public RagChatService(JdbcTemplate jdbc, VectorRetriever vectorRetriever, ElasticsearchChunkIndex keywordIndex,
                          ChatModelRouter modelRouter, FairQueueLimiter limiter, ObjectMapper mapper,
                          @Qualifier("searchExecutor") Executor searchExecutor, McpToolClient mcpToolClient,
                          IntentClassifier intentClassifier, ConversationMemory conversationMemory, RerankService rerankService,
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
        String memorySummary = "";
        String intentId = "";
        List<Citation> citations = List.of();
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
                ConversationMemory.Context context = conversationMemory.loadAndRewrite(conversationId, pending.question());
                contextualQuery = context.rewrittenQuestion();
                memorySummary = context.summary();
                if (!"__general__".equals(selected.id())) {
                    contextualQuery += "\n用户澄清方向：" + selected.name();
                    intent = selected;
                } else {
                    intent = null;
                }
                traceQuestion = pending.question() + "（澄清方向：" + selected.name() + "）";
                jdbc.update("INSERT INTO conversation_message(id,conversation_id,role,content) VALUES (?,?, 'user', ?)",
                        UUID.randomUUID(), conversationId, selected.name());
                int cleared = jdbc.update("UPDATE conversation SET pending_question=NULL,pending_intents='[]'::jsonb WHERE id=? AND owner_id=? AND pending_question IS NOT NULL",
                        conversationId, userId);
                if (cleared != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "澄清状态已被处理，请重新提问");
            } else {
                ConversationMemory.Context context = conversationMemory.loadAndRewrite(conversationId, request.question());
                contextualQuery = context.rewrittenQuestion();
                memorySummary = context.summary();
                jdbc.update("INSERT INTO conversation_message(id,conversation_id,role,content) VALUES (?,?, 'user', ?)",
                        UUID.randomUUID(), conversationId, request.question());
                IntentClassifier.Decision decision = intentClassifier.analyze(contextualQuery);
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
            }
            if (intent != null) {
                intentId = intent.id();
                send(emitter, "intent", Map.of("name", intent.name(), "confidence", intent.confidence(), "matchedKeywords", intent.matchedKeywords()));
            }
            send(emitter, "status", Map.of("stage", "retrieving", "message", "正在并行检索知识"));
            UUID kb = request.knowledgeBaseId();
            String retrievalQuery = contextualQuery;
            CompletableFuture<RetrievalResult<VectorRetriever.SearchHit>> vectorFuture = CompletableFuture.supplyAsync(
                    () -> retrieve("vector", () -> vectorRetriever.search(kb, retrievalQuery, 30), traceId), searchExecutor);
            CompletableFuture<RetrievalResult<ElasticsearchChunkIndex.KeywordHit>> keywordFuture = CompletableFuture.supplyAsync(
                    () -> retrieve("keyword", () -> keywordIndex.search(kb, userId, retrievalQuery, admin, 30), traceId), searchExecutor);
            RetrievalResult<VectorRetriever.SearchHit> vectorResult = vectorFuture.join();
            RetrievalResult<ElasticsearchChunkIndex.KeywordHit> keywordResult = keywordFuture.join();
            vectorAvailable = vectorResult.available();
            keywordAvailable = keywordResult.available();
            vectorError = vectorResult.error();
            keywordError = keywordResult.error();
            if (!vectorAvailable && !keywordAvailable) {
                throw new IllegalStateException("向量与关键词检索服务暂时不可用");
            }
            if (!vectorAvailable || !keywordAvailable) {
                send(emitter, "status", Map.of("stage", "retrieval_fallback", "message", "部分检索源暂不可用，继续使用可用结果"));
            }
            List<VectorRetriever.SearchHit> vectorHits = vectorResult.hits();
            List<ElasticsearchChunkIndex.KeywordHit> keywordHits = keywordResult.hits();
            vectorCount = vectorHits.size();
            keywordCount = keywordHits.size();
            citations = fuse(contextualQuery, vectorHits, keywordHits);
            send(emitter, "citations", citations);
            toolResult = mcpToolClient.invokeForQuestion(contextualQuery);
            if (!toolResult.isBlank()) send(emitter, "status", Map.of("stage", "tool", "message", "已调用业务工具"));

            String intentHint = intent == null || intent.promptHint() == null ? "" : "当前问题意图：" + intent.name() + "。回答提示：" + intent.promptHint() + "\n";
            String memoryHint = memorySummary.isBlank() ? "" : "会话记忆（只用于理解指代和上下文，不能作为事实依据）：\n" + memorySummary + "\n";
            String systemPrompt = "你是企业知识库问答助手。仅依据给定资料或 MCP 工具结果回答；两者都没有答案时明确说明未找到依据，不要编造。用中文作答，简洁清楚。\n" + memoryHint + intentHint;
            String prompt = buildPrompt(contextualQuery, citations, toolResult);
            if (citations.isEmpty() && toolResult.isBlank()) {
                answer = "我暂时没有在这个知识库中找到足够的资料来回答。请检查文档是否完成索引，或上传包含相关内容的文档。";
                sendText(emitter, answer);
                modelId = "no-evidence";
            } else {
                send(emitter, "status", Map.of("stage", "generating", "message", "正在生成回答"));
                StringBuilder generated = new StringBuilder();
                modelId = modelRouter.stream(systemPrompt, prompt, token -> {
                    if (cancelled.get()) throw new IllegalStateException("客户端已断开");
                    generated.append(token);
                    send(emitter, "token", Map.of("text", token));
                });
                answer = generated.toString();
            }
            jdbc.update("INSERT INTO conversation_message(id,conversation_id,role,content,citations,model_id) VALUES (?,?, 'assistant', ?,?::jsonb,?)",
                    UUID.randomUUID(), conversationId, answer, mapper.writeValueAsString(citations), modelId);
            jdbc.update("UPDATE conversation SET updated_at=now() WHERE id=?", conversationId);
            conversationMemory.refreshSummary(conversationId);
            state = "COMPLETED";
            send(emitter, "done", Map.of("conversationId", conversationId.toString(), "traceId", traceId.toString(), "modelId", modelId));
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
                retrievalTrace.put("memoryUsed", !memorySummary.isBlank());
                retrievalTrace.put("intentId", intentId);
                retrievalTrace.put("clarificationRequired", "NEEDS_CLARIFICATION".equals(state));
                jdbc.update("INSERT INTO rag_trace(id,owner_id,conversation_id,query_text,retrieval,model_id,status,duration_ms,error_message) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",
                        traceId, userId, conversationId, traceQuestion, mapper.writeValueAsString(retrievalTrace),
                        modelId, state, System.currentTimeMillis() - started, error);
            } catch (Exception ignored) { }
            emitter.complete();
        }
    }

    private <T> RetrievalResult<T> retrieve(String source, CheckedSupplier<List<T>> search, UUID traceId) {
        try {
            return new RetrievalResult<>(List.copyOf(search.get()), null);
        } catch (Exception unavailable) {
            log.warn("RAG {} retrieval failed for trace {}", source, traceId, unavailable);
            return new RetrievalResult<>(List.of(), unavailable.getClass().getSimpleName());
        }
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

    List<Citation> fuse(String query, List<VectorRetriever.SearchHit> vectors, List<ElasticsearchChunkIndex.KeywordHit> keywords) {
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
        if (rerankService.configured() && candidates.size() > 1) {
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
            } catch (Exception ignored) {
                // Keep the deterministic RRF + lexical order when the optional provider is unavailable.
            }
        }
        return candidates.stream()
                .limit(6)
                .map(c -> new Citation(c.chunkId, c.documentId, c.filename, c.chunkIndex, c.content.length() > 500 ? c.content.substring(0, 500) + "…" : c.content, c.rrf))
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

    public record Citation(String chunkId, String documentId, String filename, int chunkIndex, String excerpt, double score) {}
    private static final class CitationAccumulator {
        final String chunkId;
        final String documentId;
        final String filename;
        final int chunkIndex;
        final String content;
        double rrf;
        double vectorSimilarity = Double.NEGATIVE_INFINITY;
        double lexicalOverlap;
        CitationAccumulator(String chunkId, String documentId, String filename, int chunkIndex, String content) {
            this.chunkId = chunkId; this.documentId = documentId; this.filename = filename; this.chunkIndex = chunkIndex; this.content = content;
        }
    }

    private record RetrievalResult<T>(List<T> hits, String error) {
        boolean available() { return error == null; }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
