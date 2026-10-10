package com.intelhub.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import com.intelhub.rag.RagChatService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/admin/evaluation")
public class RagEvaluationController {
    private static final int TOP_K = 6;
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper;
    private final RagChatService ragChatService;

    public RagEvaluationController(JdbcTemplate jdbc, CurrentUser currentUser, ObjectMapper mapper, RagChatService ragChatService) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.ragChatService = ragChatService;
    }

    @GetMapping("/cases")
    public ApiResult<EvaluationDatasetView> cases() {
        currentUser.requireAdmin();
        return ApiResult.ok(loadDataset());
    }

    @PostMapping("/run")
    public ApiResult<EvaluationRunView> run(@RequestBody EvaluationRunRequest request) {
        currentUser.requireAdmin();
        UUID userId = currentUser.id();
        if (request == null || request.knowledgeBaseId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择用于评测的知识库");
        }
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM knowledge_base WHERE id=?", Integer.class, request.knowledgeBaseId());
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");

        EvaluationDatasetView dataset = loadDataset();
        long started = System.currentTimeMillis();
        List<EvaluationCaseResult> results = new ArrayList<>();
        int sourceHitAt1 = 0;
        int sourceHitAtK = 0;
        int evidenceMatches = 0;
        int evidenceTotal = 0;
        double reciprocalRankTotal = 0;
        for (EvaluationCase evaluationCase : dataset.cases()) {
            List<RagChatService.Citation> retrieved;
            String error = null;
            try {
                retrieved = ragChatService.retrieveForEvaluation(request.knowledgeBaseId(), userId, true, evaluationCase.question());
            } catch (Exception exception) {
                retrieved = List.of();
                error = safeError(exception);
            }
            List<RagChatService.Citation> retrievedHits = retrieved;
            int sourceRank = firstSourceRank(retrievedHits, evaluationCase.expectedSourceContains());
            if (sourceRank == 1) sourceHitAt1++;
            if (sourceRank > 0) {
                sourceHitAtK++;
                reciprocalRankTotal += 1.0 / sourceRank;
            }
            List<String> matchedEvidence = evaluationCase.expectedEvidence().stream()
                    .filter(expected -> retrievedHits.stream().anyMatch(hit -> containsNormalized(hit.excerpt(), expected)))
                    .toList();
            evidenceMatches += matchedEvidence.size();
            evidenceTotal += evaluationCase.expectedEvidence().size();
            boolean allExpectedEvidenceMatched = !evaluationCase.expectedEvidence().isEmpty()
                    && matchedEvidence.size() == evaluationCase.expectedEvidence().size();
            List<EvaluationHit> hits = retrievedHits.stream().limit(TOP_K)
                    .map(hit -> new EvaluationHit(hit.chunkId(), hit.filename(), hit.chunkIndex(), hit.excerpt(),
                            containsNormalized(hit.filename(), evaluationCase.expectedSourceContains())))
                    .toList();
            results.add(new EvaluationCaseResult(evaluationCase.id(), evaluationCase.title(), evaluationCase.question(),
                    evaluationCase.expectedSourceContains(), sourceRank, matchedEvidence,
                    sourceRank > 0 && allExpectedEvidenceMatched, error, hits));
        }

        int total = results.size();
        EvaluationMetrics metrics = new EvaluationMetrics(total, results.stream().mapToInt(result -> result.passed() ? 1 : 0).sum(),
                ratio(sourceHitAt1, total), ratio(sourceHitAtK, total), ratio(reciprocalRankTotal, total),
                ratio(evidenceMatches, evidenceTotal), TOP_K, System.currentTimeMillis() - started);
        return ApiResult.ok(new EvaluationRunView(request.knowledgeBaseId().toString(), dataset.version(), metrics, results));
    }

    private EvaluationDatasetView loadDataset() {
        try (InputStream stream = new ClassPathResource("evaluation/rag-smoke-set.json").getInputStream()) {
            return mapper.readValue(stream, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("问答评测集读取失败", exception);
        }
    }

    private static int firstSourceRank(List<RagChatService.Citation> hits, String source) {
        for (int i = 0; i < Math.min(TOP_K, hits.size()); i++) {
            if (containsNormalized(hits.get(i).filename(), source)) return i + 1;
        }
        return 0;
    }

    private static boolean containsNormalized(String value, String expected) {
        if (value == null || expected == null) return false;
        return normalize(value).contains(normalize(expected));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    public record EvaluationRunRequest(UUID knowledgeBaseId) {}
    public record EvaluationDatasetView(String version, List<EvaluationCase> cases) {}
    public record EvaluationCase(String id, String title, String question, String expectedSourceContains, List<String> expectedEvidence) {}
    public record EvaluationRunView(String knowledgeBaseId, String datasetVersion, EvaluationMetrics metrics, List<EvaluationCaseResult> results) {}
    public record EvaluationMetrics(int caseCount, int passedCases, double sourceHitAt1, double sourceRecallAt6,
                                    double mrrAt6, double evidenceCoverage, int topK, long durationMs) {}
    public record EvaluationCaseResult(String id, String title, String question, String expectedSourceContains,
                                       int sourceRank, List<String> matchedEvidence, boolean passed, String error, List<EvaluationHit> retrieved) {}
    public record EvaluationHit(String chunkId, String filename, int chunkIndex, String excerpt, boolean expectedSource) {}
}
