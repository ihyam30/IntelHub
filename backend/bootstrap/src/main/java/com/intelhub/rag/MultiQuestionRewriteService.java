package com.intelhub.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.ai.ChatModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Rewrites a user query and decomposes compound questions before retrieval. */
@Component
public class MultiQuestionRewriteService {
    private static final Logger log = LoggerFactory.getLogger(MultiQuestionRewriteService.class);
    private static final int MAX_SUB_QUESTIONS = 4;
    private static final String SYSTEM_PROMPT = """
            你负责为企业知识库检索改写问题。保留用户原意、实体、编号、时间和约束；
            如果一个输入包含多个可以独立检索的问题，将它们拆成最多 4 个子问题；否则只返回一个问题。
            不回答问题，不添加原文没有的事实。只返回 JSON：
            {"rewritten_question":"归一化后的完整问题","sub_questions":["子问题一","子问题二"]}
            """;

    private static final Pattern RULE_BOUNDARY = Pattern.compile("([?？。；;!！\\r\\n]+)");
    private static final Pattern COMPOUND_JOINER = Pattern.compile("\\s*[，,]?\\s*(?:另外|此外|同时|并且|以及|还有|再加上)\\s*[，,]?\\s*");
    private static final Pattern QUESTION_SIGNAL = Pattern.compile("(?:请问|什么|怎么|如何|哪些|多少|是否|能否|可否|能不能|为什么|为何|哪里|何时|多久|几天|吗|呢|哪一个|哪个)");
    private static final Pattern REQUEST_SIGNAL = Pattern.compile("(?:请(?:问|帮我|总结|介绍|列出|说明|比较|分析|解释|概括|查询|查找|告诉|提供)|帮我(?:查询|查找|总结|介绍|列出|说明|分析|解释|比较|提供)|(?:总结|介绍|列出|说明|比较|分析|解释|概括).{0,24}(?:内容|区别|流程|情况|要求|规则|信息|方案))");

    private final ChatModelRouter modelRouter;
    private final ObjectMapper mapper;

    public MultiQuestionRewriteService(ChatModelRouter modelRouter, ObjectMapper mapper) {
        this.modelRouter = modelRouter;
        this.mapper = mapper;
    }

    public RewriteResult rewrite(String question) {
        String original = question == null ? "" : question.trim();
        if (original.isBlank()) return new RewriteResult(original, List.of(), "none", null);

        try {
            String response = modelRouter.complete(SYSTEM_PROMPT, original);
            RewriteResult parsed = parse(response, original);
            if (parsed != null) return new RewriteResult(parsed.rewrittenQuestion(), parsed.subQuestions(), "model", null);
            String fallbackReason = response == null || response.isBlank() ? "模型未返回改写结果" : "模型返回格式无效";
            return new RewriteResult(original, splitByRules(original), "deterministic", fallbackReason);
        } catch (Exception exception) {
            log.debug("Query rewrite provider failed; using deterministic split", exception);
            return new RewriteResult(original, splitByRules(original), "deterministic", safeError(exception));
        }
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        String result = exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return result.length() <= 500 ? result : result.substring(0, 500);
    }

    private RewriteResult parse(String response, String original) {
        if (response == null || response.isBlank()) return null;
        try {
            String json = response.trim()
                    .replaceFirst("(?s)^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) return null;
            String rewritten = text(root, "rewritten_question");
            if (rewritten.isBlank()) rewritten = text(root, "rewrite");
            if (rewritten.isBlank()) return null;

            LinkedHashSet<String> subQuestions = new LinkedHashSet<>();
            JsonNode values = root.has("sub_questions") ? root.get("sub_questions") : root.get("subQuestions");
            if (values != null && values.isArray()) {
                for (JsonNode value : values) {
                    if (value.isTextual() && !value.asText().isBlank()) {
                        subQuestions.add(normalizeQuestion(value.asText()));
                        if (subQuestions.size() == MAX_SUB_QUESTIONS) break;
                    }
                }
            }
            if (subQuestions.isEmpty()) subQuestions.addAll(splitByRules(rewritten));
            if (subQuestions.isEmpty()) subQuestions.add(normalizeQuestion(rewritten));
            return new RewriteResult(rewritten.trim(), List.copyOf(subQuestions));
        } catch (Exception invalidResponse) {
            log.debug("Query rewrite response is not valid JSON; using deterministic split");
            return null;
        }
    }

    private static String text(JsonNode root, String key) {
        JsonNode value = root.get(key);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    static List<String> splitByRules(String question) {
        List<String> parts = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        java.util.regex.Matcher matcher = RULE_BOUNDARY.matcher(question);
        int start = 0;
        while (matcher.find()) {
            collectRuleSegment(question.substring(start, matcher.start()), matcher.group(), parts, context);
            start = matcher.end();
        }
        collectRuleSegment(question.substring(start), "", parts, context);

        if (!context.isEmpty()) {
            String suffixContext = context.toString().trim();
            if (parts.isEmpty()) parts.add(normalizeQuestion(suffixContext));
            else parts.set(parts.size() - 1, normalizeQuestion(suffixContext + parts.get(parts.size() - 1)));
        }
        return parts.stream().distinct().limit(MAX_SUB_QUESTIONS).toList();
    }

    private static void collectRuleSegment(String segment, String delimiter, List<String> parts, StringBuilder context) {
        String trimmed = segment.trim();
        if (trimmed.isBlank()) return;

        boolean explicitQuestionEnd = delimiter.contains("?") || delimiter.contains("？");
        if (!explicitQuestionEnd && !looksLikeQuestionOrRequest(trimmed)) {
            if (!context.isEmpty() && !context.toString().endsWith("。") && !context.toString().endsWith("；")) context.append("。");
            context.append(trimmed);
            if (delimiter.contains("！")) context.append("！");
            else if (delimiter.contains("!")) context.append("!");
            else if (delimiter.contains("；") || delimiter.contains(";")) context.append("；");
            else if (delimiter.contains("\n") || delimiter.contains("\r")) context.append("。");
            else if (delimiter.contains("。")) context.append("。");
            return;
        }

        String completeQuestion = context + trimmed;
        context.setLength(0);
        java.util.regex.Matcher leadingJoiner = COMPOUND_JOINER.matcher(completeQuestion);
        String normalizedClauses = leadingJoiner.lookingAt() ? leadingJoiner.replaceFirst("") : completeQuestion;
        String[] candidates = COMPOUND_JOINER.split(normalizedClauses);
        List<String> nonBlankCandidates = java.util.Arrays.stream(candidates).map(String::trim).filter(value -> !value.isBlank()).toList();
        if (nonBlankCandidates.size() > 1 && nonBlankCandidates.stream().allMatch(MultiQuestionRewriteService::looksLikeQuestionOrRequest)) {
            for (String candidate : nonBlankCandidates) parts.add(normalizeQuestion(candidate));
        } else {
            parts.add(normalizeQuestion(normalizedClauses));
        }
    }

    private static boolean looksLikeQuestionOrRequest(String text) {
        return QUESTION_SIGNAL.matcher(text).find() || REQUEST_SIGNAL.matcher(text).find();
    }

    private static String normalizeQuestion(String value) {
        String result = value.trim();
        if (!result.endsWith("？") && !result.endsWith("?")) result += "？";
        return result;
    }

    public record RewriteResult(String rewrittenQuestion, List<String> subQuestions, String strategy, String error) {
        public RewriteResult(String rewrittenQuestion, List<String> subQuestions) {
            this(rewrittenQuestion, subQuestions, "deterministic", null);
        }
    }
}
