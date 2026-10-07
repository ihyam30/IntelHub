package com.intelhub.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Component
public class ConversationMemory {
    private static final int SUMMARY_INTERVAL = 6;
    private static final int MAX_SUMMARY_LENGTH = 1800;
    private final JdbcTemplate jdbc;

    public ConversationMemory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Context rewrite(String question, String summary, List<Message> history) {
        List<Message> ordered = new ArrayList<>(history);
        Collections.reverse(ordered);
        String previousQuestion = lastContent(ordered, "user");
        String previousAnswer = lastContent(ordered, "assistant");
        if (!isFollowUp(question, ordered)) return new Context(question.trim(), summary == null ? "" : summary);

        StringBuilder rewritten = new StringBuilder();
        if (summary != null && !summary.isBlank()) rewritten.append("长期会话记忆：").append(summary.trim()).append("\n");
        if (!previousQuestion.isBlank()) rewritten.append("此前问题：").append(limit(previousQuestion, 240)).append("\n");
        if (!previousAnswer.isBlank()) rewritten.append("此前回答：").append(limit(previousAnswer, 360)).append("\n");
        rewritten.append("当前追问：").append(question.trim());
        return new Context(rewritten.toString(), summary == null ? "" : summary);
    }

    public Context loadAndRewrite(java.util.UUID conversationId, String question) {
        List<String> summaries = jdbc.query("SELECT summary FROM conversation WHERE id=?",
                (rs, row) -> rs.getString(1), conversationId);
        List<Message> history = jdbc.query("SELECT role,content FROM conversation_message WHERE conversation_id=? ORDER BY created_at DESC LIMIT 8",
                (rs, row) -> new Message(rs.getString("role"), rs.getString("content")), conversationId);
        return rewrite(question, summaries.isEmpty() ? "" : summaries.get(0), history);
    }

    public void refreshSummary(java.util.UUID conversationId) {
        List<SummaryState> states = jdbc.query("SELECT COALESCE(summary,''),summary_message_count FROM conversation WHERE id=?",
                (rs, row) -> new SummaryState(rs.getString(1), rs.getLong(2)), conversationId);
        if (states.isEmpty()) return;
        SummaryState state = states.get(0);
        Long total = jdbc.queryForObject("SELECT count(*) FROM conversation_message WHERE conversation_id=?", Long.class, conversationId);
        if (total == null || total < 10 || total - state.messageCount() < SUMMARY_INTERVAL) return;
        List<Message> delta = jdbc.query("SELECT role,content FROM conversation_message WHERE conversation_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
                (rs, row) -> new Message(rs.getString("role"), rs.getString("content")), conversationId,
                Math.toIntExact(total - state.messageCount()), Math.toIntExact(state.messageCount()));
        String summary = summarize(state.summary(), delta);
        jdbc.update("UPDATE conversation SET summary=?,summary_message_count=?,updated_at=now() WHERE id=?",
                summary, total, conversationId);
    }

    static boolean isFollowUp(String question, List<Message> history) {
        if (history.isEmpty() || question == null || question.isBlank() || question.length() > 180) return false;
        String value = question.strip().toLowerCase(Locale.ROOT);
        return value.matches("^(它|他|她|这|那|其|上述|上面|刚才|前面|这个|那个|第二个|第一个|还有|那么|为什么|怎么|如何|再|详细|具体|what\\b|how\\b|why\\b|and\\b|also\\b).*")
                || value.matches("^(那|这|它|其|what|how|why).{0,12}(呢|吗|？|\\?).*$");
    }

    static String summarize(String previous, List<Message> messages) {
        LinkedHashSet<String> facts = new LinkedHashSet<>();
        if (previous != null && !previous.isBlank()) {
            for (String line : previous.split("\\R")) if (!line.isBlank()) facts.add(line.trim());
        }
        String latestQuestion = "";
        for (Message message : messages) {
            String content = clean(message.content());
            if (content.isBlank()) continue;
            if ("user".equals(message.role())) {
                latestQuestion = content;
                facts.add("关注：" + limit(content, 180));
            } else if ("assistant".equals(message.role())) {
                answerSentences(content, latestQuestion).forEach(sentence -> facts.add("结论：" + limit(sentence, 260)));
            }
        }
        List<String> bounded = facts.stream().skip(Math.max(0, facts.size() - 10L)).toList();
        String summary = String.join("\n", bounded);
        return summary.length() <= MAX_SUMMARY_LENGTH ? summary : summary.substring(summary.length() - MAX_SUMMARY_LENGTH);
    }

    private static List<String> answerSentences(String answer, String question) {
        return java.util.Arrays.stream(answer.split("(?<=[。！？.!?])\\s*|\\R"))
                .map(String::trim)
                .filter(sentence -> sentence.length() >= 8)
                .filter(sentence -> !sentence.startsWith("我暂时没有") && !sentence.startsWith("模型服务暂时不可用"))
                .sorted(java.util.Comparator.comparingInt((String sentence) -> relevance(sentence, question)).reversed())
                .limit(2).toList();
    }

    private static int relevance(String sentence, String question) {
        int score = Math.min(sentence.length(), 120) / 20;
        if (sentence.matches(".*[0-9一二三四五六七八九十%￥$].*")) score += 2;
        for (String token : question.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && sentence.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT))) score += 3;
        }
        return score;
    }

    private static String lastContent(List<Message> messages, String role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (role.equals(messages.get(i).role())) return messages.get(i).content();
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String limit(String value, int max) {
        String clean = clean(value);
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    public record Message(String role, String content) {}
    public record Context(String rewrittenQuestion, String summary) {}
    private record SummaryState(String summary, long messageCount) {}
}
