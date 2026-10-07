package com.intelhub.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.ai.ChatModelRouter;
import com.intelhub.ai.EmbeddingService;
import com.intelhub.ai.RerankService;
import com.intelhub.common.CurrentUser;
import com.intelhub.ingestion.ChunkerNode;
import com.intelhub.ingestion.DocumentIngestionConsumer;
import com.intelhub.ingestion.DocumentIngestionProducer;
import com.intelhub.ingestion.EmbeddingNode;
import com.intelhub.ingestion.IndexerNode;
import com.intelhub.ingestion.MetadataNode;
import com.intelhub.ingestion.NormalizeNode;
import com.intelhub.ingestion.ParserNode;
import com.intelhub.mcp.McpToolClient;
import com.intelhub.rag.ConversationMemory;
import com.intelhub.rag.FairQueueLimiter;
import com.intelhub.rag.IntentClassifier;
import com.intelhub.rag.RagChatController;
import com.intelhub.rag.RagChatService;
import com.intelhub.rag.VectorRetriever;
import com.intelhub.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentToAnswerFlowTest {
    private static final UUID OWNER_ID = UUID.fromString("eb68ad4e-3ac4-477e-b322-1554f83352b7");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("b27f8939-ce5e-4d27-a21d-c15b65461923");
    private static final UUID PIPELINE_ID = UUID.fromString("2f40f189-5734-4f03-babc-a24020e05abc");

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void uploadsParsesIndexesRetrievesAndAnswersWithTheIndexedCitation() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        DocumentIngestionProducer producer = mock(DocumentIngestionProducer.class);
        ElasticsearchChunkIndex keywordIndex = mock(ElasticsearchChunkIndex.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> locks = mock(ValueOperations.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        ChatModelRouter modelRouter = mock(ChatModelRouter.class);
        FairQueueLimiter limiter = mock(FairQueueLimiter.class);
        FairQueueLimiter.Lease lease = mock(FairQueueLimiter.Lease.class);
        McpToolClient mcp = mock(McpToolClient.class);
        IntentClassifier intents = mock(IntentClassifier.class);
        ConversationMemory memory = mock(ConversationMemory.class);
        RerankService reranker = mock(RerankService.class);
        ObjectMapper mapper = new ObjectMapper();
        ExecutorService searchExecutor = Executors.newFixedThreadPool(2);
        try {
            byte[] original = "Annual leave policy: employees receive fifteen days of annual leave each year."
                    .getBytes(StandardCharsets.UTF_8);
            when(currentUser.id()).thenReturn(OWNER_ID);
            when(currentUser.isAdmin()).thenReturn(false);
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
            when(storage.get(anyString())).thenReturn(original);
            when(redis.opsForValue()).thenReturn(locks);
            when(locks.setIfAbsent(anyString(), anyString(), eq(Duration.ofMinutes(20)))).thenReturn(true);
            when(jdbc.queryForObject(contains("SELECT id FROM ingestion_pipeline"), eq(UUID.class))).thenReturn(PIPELINE_ID);
            when(jdbc.queryForObject(contains("SELECT nodes FROM ingestion_pipeline"), eq(String.class), eq(PIPELINE_ID), eq(OWNER_ID)))
                    .thenReturn("[{\"type\":\"parse\"},{\"type\":\"normalize\"},{\"type\":\"chunk\"},{\"type\":\"metadata\"},{\"type\":\"embedding\"},{\"type\":\"index\"}]");
            when(embeddings.embed(anyString())).thenReturn(new float[]{0.2f, -0.1f, 0.7f});
            when(embeddings.dimension()).thenReturn(3);

            var upload = new DocumentController(jdbc, currentUser, storage, producer, keywordIndex)
                    .upload(KNOWLEDGE_BASE_ID, new MockMultipartFile("file", "policy.txt", "text/plain", original), null);
            org.mockito.ArgumentCaptor<UUID> documentIdCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
            verify(producer).enqueue(documentIdCaptor.capture());
            UUID documentId = documentIdCaptor.getValue();
            assertThat(upload.data().id()).isEqualTo(documentId.toString());

            ResultSet documentRow = mock(ResultSet.class);
            when(documentRow.getObject("id", UUID.class)).thenReturn(documentId);
            when(documentRow.getObject("knowledge_base_id", UUID.class)).thenReturn(KNOWLEDGE_BASE_ID);
            when(documentRow.getObject("owner_id", UUID.class)).thenReturn(OWNER_ID);
            when(documentRow.getObject("pipeline_id", UUID.class)).thenReturn(null);
            when(documentRow.getString("filename")).thenReturn("policy.txt");
            when(documentRow.getString("object_key")).thenReturn(OWNER_ID + "/" + KNOWLEDGE_BASE_ID + "/" + documentId + "/policy.txt");
            when(documentRow.getString("status")).thenReturn("QUEUED");
            doAnswer(invocation -> {
                RowMapper rowMapper = invocation.getArgument(1);
                return List.of(rowMapper.mapRow(documentRow, 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

            DocumentIngestionConsumer consumer = new DocumentIngestionConsumer(jdbc, redis, mapper, List.of(
                    new ParserNode(storage), new NormalizeNode(), new ChunkerNode(), new MetadataNode(),
                    new EmbeddingNode(embeddings), new IndexerNode(jdbc, keywordIndex, mapper)));
            consumer.onMessage(documentId.toString());

            org.mockito.ArgumentCaptor<UUID> chunkIdCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
            org.mockito.ArgumentCaptor<String> contentCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(keywordIndex).index(chunkIdCaptor.capture(), eq(documentId), eq(KNOWLEDGE_BASE_ID), eq(OWNER_ID), eq("policy.txt"), contentCaptor.capture());
            UUID chunkId = chunkIdCaptor.getValue();
            String indexedContent = contentCaptor.getValue();
            assertThat(indexedContent).contains("fifteen days of annual leave");
            verify(jdbc).update(contains("SET status='COMPLETED',chunk_count=?"), eq(1), eq(documentId));

            when(vectorRetriever.search(eq(KNOWLEDGE_BASE_ID), anyString(), eq(30))).thenReturn(List.of(
                    new VectorRetriever.SearchHit(chunkId.toString(), documentId.toString(), "policy.txt", 0,
                            indexedContent, 0.92, "vector")));
            when(keywordIndex.search(eq(KNOWLEDGE_BASE_ID), eq(OWNER_ID), anyString(), eq(false), eq(30))).thenReturn(List.of(
                    new ElasticsearchChunkIndex.KeywordHit(chunkId.toString(), documentId.toString(), "policy.txt", indexedContent, 4.0)));
            when(limiter.acquire(eq(OWNER_ID), any(AtomicBoolean.class), any())).thenReturn(lease);
            when(memory.loadAndRewrite(any(UUID.class), anyString())).thenAnswer(invocation ->
                    new ConversationMemory.Context(invocation.getArgument(1), ""));
            when(intents.analyze(anyString())).thenReturn(new IntentClassifier.Decision(
                    new IntentClassifier.Classification("policy", "制度咨询", "制度", "", 0.9, List.of("制度")), List.of(), false));
            when(mcp.invokeForQuestion(anyString())).thenReturn("");
            doAnswer(invocation -> {
                Consumer<String> onToken = invocation.getArgument(2);
                onToken.accept("制度资料说明：每位员工每年享有十五天年假。");
                return "integration-model";
            }).when(modelRouter).stream(anyString(), anyString(), any());

            RagChatService chatService = new RagChatService(jdbc, vectorRetriever, keywordIndex, modelRouter, limiter,
                    mapper, searchExecutor, mcp, intents, memory, reranker, 0.25, 0.1);
            chatService.stream(OWNER_ID, false,
                    new RagChatController.ChatRequest(KNOWLEDGE_BASE_ID, null, "制度里每年有多少天年假？"),
                    new SseEmitter(30_000L), new AtomicBoolean());

            org.mockito.ArgumentCaptor<Object[]> assistantMessage = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).update(contains("INSERT INTO conversation_message(id,conversation_id,role,content,citations"), assistantMessage.capture());
            assertThat(assistantMessage.getValue()[2]).isEqualTo("制度资料说明：每位员工每年享有十五天年假。");
            JsonNode citations = mapper.readTree(assistantMessage.getValue()[3].toString());
            assertThat(citations).hasSize(1);
            assertThat(citations.get(0).path("filename").asText()).isEqualTo("policy.txt");
            assertThat(citations.get(0).path("chunkId").asText()).isEqualTo(chunkId.toString());

            org.mockito.ArgumentCaptor<Object[]> trace = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).update(contains("INSERT INTO rag_trace"), trace.capture());
            JsonNode retrieval = mapper.readTree(trace.getValue()[4].toString());
            assertThat(retrieval.path("vectorHits").asInt()).isEqualTo(1);
            assertThat(retrieval.path("keywordHits").asInt()).isEqualTo(1);
            assertThat(retrieval.path("citations").asInt()).isEqualTo(1);
            assertThat(trace.getValue()[6]).isEqualTo("COMPLETED");
            verify(lease).close();
        } finally {
            searchExecutor.shutdownNow();
        }
    }
}
