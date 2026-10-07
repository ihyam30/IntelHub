package com.intelhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class ChatModelRouter {
    private final List<ChatModelClient> clients;
    private final StringRedisTemplate redis;
    private final int failureThreshold;
    private final long cooldownMillis;

    public ChatModelRouter(ObjectMapper mapper,
                           @Value("${intelhub.ai.primary.base-url:}") String primaryUrl,
                           @Value("${intelhub.ai.primary.api-key:}") String primaryKey,
                           @Value("${intelhub.ai.primary.model:}") String primaryModel,
                           @Value("${intelhub.ai.fallback.base-url:}") String fallbackUrl,
                           @Value("${intelhub.ai.fallback.api-key:}") String fallbackKey,
                           @Value("${intelhub.ai.fallback.model:}") String fallbackModel,
                           StringRedisTemplate redis,
                           @Value("${intelhub.ai.chat.circuit.failure-threshold:3}") int failureThreshold,
                           @Value("${intelhub.ai.chat.circuit.cooldown-millis:30000}") long cooldownMillis) {
        if (failureThreshold < 1 || cooldownMillis < 1) {
            throw new IllegalArgumentException("模型熔断阈值和冷却时间必须大于 0");
        }
        List<ChatModelClient> configured = new ArrayList<>();
        if (valid(primaryUrl, primaryModel)) configured.add(new OpenAiCompatibleChatModelClient("primary", primaryUrl, primaryKey, primaryModel, mapper));
        if (valid(fallbackUrl, fallbackModel)) configured.add(new OpenAiCompatibleChatModelClient("fallback", fallbackUrl, fallbackKey, fallbackModel, mapper));
        this.clients = List.copyOf(configured);
        this.redis = redis;
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    public String stream(String systemPrompt, String userPrompt, Consumer<String> onToken) throws Exception {
        if (clients.isEmpty()) {
            String answer = localFallback(userPrompt, false);
            for (int i = 0; i < answer.length(); i += 12) onToken.accept(answer.substring(i, Math.min(i + 12, answer.length())));
            return "local-demo";
        }

        for (ChatModelClient client : clients) {
            if (!allowRequest(client.id())) continue;
            boolean[] emitted = {false};
            try {
                client.stream(systemPrompt, userPrompt, token -> {
                    emitted[0] = true;
                    onToken.accept(token);
                });
                recordSuccess(client.id());
                return client.id();
            } catch (Exception ex) {
                recordFailure(client.id());
                if (emitted[0]) throw ex;
            }
        }
        String fallback = localFallback(userPrompt, true);
        for (int i = 0; i < fallback.length(); i += 12) onToken.accept(fallback.substring(i, Math.min(i + 12, fallback.length())));
        return "local-demo-fallback";
    }

    public List<Map<String, Object>> health() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int priority = 0; priority < clients.size(); priority++) {
            ChatModelClient client = clients.get(priority);
            String key = "intelhub:model:circuit:" + client.id();
            String failures = (String) redis.opsForHash().get(key, "failures");
            String openUntilValue = (String) redis.opsForHash().get(key, "openUntil");
            long openUntil = openUntilValue == null ? 0 : parseLong(openUntilValue);
            String state = openUntil == 0 ? "CLOSED" : System.currentTimeMillis() < openUntil ? "OPEN" : "HALF_OPEN";
            result.add(Map.of("id", client.id(), "model", client.model(), "priority", priority + 1,
                    "state", state, "failures", failures == null ? 0 : parseLong(failures),
                    "openUntil", openUntil == 0 ? "" : Instant.ofEpochMilli(openUntil).toString()));
        }
        if (result.isEmpty()) result.add(Map.of("id", "local-demo", "model", "local-demo", "priority", 1,
                "state", "CLOSED", "failures", 0, "openUntil", ""));
        return result;
    }

    private static boolean valid(String url, String model) {
        return url != null && !url.isBlank() && model != null && !model.isBlank();
    }

    private static String localFallback(String prompt, boolean providersFailed) {
        int start = prompt.indexOf("参考资料：");
        String evidence = start < 0 ? "" : prompt.substring(start + "参考资料：".length()).trim();
        if (evidence.length() > 700) evidence = evidence.substring(0, 700) + "…";
        if (evidence.isBlank()) return providersFailed ? "模型服务暂时不可用，且没有可引用的本地资料。请稍后重试。" : "当前未配置外部模型。请配置模型密钥后启用生成服务。";
        return (providersFailed ? "模型服务暂时不可用。" : "本地演示模式暂未配置外部模型。") +
                "我找到以下相关资料片段，供你核对：\n\n" + evidence;
    }

    private boolean allowRequest(String id) {
        String key = "intelhub:model:circuit:" + id;
        String value = (String) redis.opsForHash().get(key, "openUntil");
        long openUntil = value == null ? 0 : parseLong(value);
        if (openUntil == 0) return true;
        if (System.currentTimeMillis() < openUntil) return false;
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key + ":probe", "1", Duration.ofSeconds(60)));
    }

    private void recordSuccess(String id) {
        String key = "intelhub:model:circuit:" + id;
        redis.opsForHash().put(key, "failures", "0");
        redis.opsForHash().put(key, "openUntil", "0");
        redis.delete(key + ":probe");
    }

    private void recordFailure(String id) {
        String key = "intelhub:model:circuit:" + id;
        Long failures = redis.opsForHash().increment(key, "failures", 1);
        String current = (String) redis.opsForHash().get(key, "openUntil");
        long openUntil = current == null ? 0 : parseLong(current);
        if ((failures != null && failures >= failureThreshold) || openUntil > 0) {
            redis.opsForHash().put(key, "openUntil", Long.toString(System.currentTimeMillis() + cooldownMillis));
        }
        redis.delete(key + ":probe");
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0; }
    }
}
