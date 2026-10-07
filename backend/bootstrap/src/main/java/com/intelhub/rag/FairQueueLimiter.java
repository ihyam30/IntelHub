package com.intelhub.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

@Component
public class FairQueueLimiter {
    private static final String QUEUE = "intelhub:chat:queue";
    private static final String PERMITS = "intelhub:chat:permits";
    private static final String QUEUE_TIMES = "intelhub:chat:queue-times";
    private static final String SEQUENCE = "intelhub:chat:sequence";
    private static final String CHANNEL = "intelhub:chat:permit-released";
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[1]); " +
                    "while true do local head=redis.call('ZRANGE', KEYS[1], 0, 0); if #head == 0 then break end; " +
                    "local queued=redis.call('HGET', KEYS[3], head[1]); if not queued or tonumber(queued) < tonumber(ARGV[1]) - tonumber(ARGV[5]) then " +
                    "redis.call('ZREM', KEYS[1], head[1]); redis.call('HDEL', KEYS[3], head[1]); else break end end; " +
                    "local rank=redis.call('ZRANK', KEYS[1], ARGV[2]); " +
                    "if not rank then return -1 end; " +
                    "if rank == 0 and redis.call('ZCARD', KEYS[2]) < tonumber(ARGV[3]) then " +
                    "redis.call('ZREM', KEYS[1], ARGV[2]); redis.call('HDEL', KEYS[3], ARGV[2]); redis.call('ZADD', KEYS[2], ARGV[4], ARGV[2]); return 0 end; " +
                    "return rank + 1;", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("return redis.call('ZREM', KEYS[1], ARGV[1]);", Long.class);
    private static final DefaultRedisScript<Long> USER_WINDOW = new DefaultRedisScript<>(
            "local n=redis.call('INCR', KEYS[1]); if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; return n;", Long.class);

    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;
    private volatile int maxConcurrent;
    private final int maxWaitSeconds;
    private final int leaseSeconds;
    private final Object wakeMonitor = new Object();
    private RedisMessageListenerContainer listenerContainer;

    public FairQueueLimiter(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
                            @Value("${intelhub.limiter.max-concurrent}") int maxConcurrent,
                            @Value("${intelhub.limiter.max-wait-seconds}") int maxWaitSeconds,
                            @Value("${intelhub.limiter.lease-seconds}") int leaseSeconds) {
        this.redis = redis;
        this.connectionFactory = connectionFactory;
        this.maxConcurrent = maxConcurrent;
        this.maxWaitSeconds = maxWaitSeconds;
        this.leaseSeconds = leaseSeconds;
    }

    @PostConstruct
    void subscribeToReleaseEvents() {
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.addMessageListener((MessageListener) this::onMessage, new ChannelTopic(CHANNEL));
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
    }

    @PreDestroy
    void stopListener() {
        if (listenerContainer != null) listenerContainer.stop();
    }

    public Lease acquire(UUID userId, AtomicBoolean cancelled, IntConsumer onPosition) throws InterruptedException {
        Long used = redis.execute(USER_WINDOW, List.of("intelhub:chat:user-window:" + userId), "60");
        if (used != null && used > 20) throw new IllegalStateException("每个账号每分钟最多发起 20 次问答");

        String ticket = UUID.randomUUID().toString();
        Long order = redis.opsForValue().increment(SEQUENCE);
        redis.opsForZSet().add(QUEUE, ticket, order == null ? System.nanoTime() : order.doubleValue());
        redis.opsForHash().put(QUEUE_TIMES, ticket, Long.toString(System.currentTimeMillis()));
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        boolean acquired = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("问答请求已取消");
                long now = System.currentTimeMillis();
                Long result = redis.execute(ACQUIRE, List.of(QUEUE, PERMITS, QUEUE_TIMES), Long.toString(now), ticket,
                        Integer.toString(currentMaxConcurrent()), Long.toString(now + leaseSeconds * 1000L), Long.toString(maxWaitSeconds * 1000L));
                if (result != null && result == 0L) {
                    acquired = true;
                    return new Lease(ticket);
                }
                int position = result == null || result < 0 ? 0 : result.intValue();
                onPosition.accept(position);
                synchronized (wakeMonitor) { wakeMonitor.wait(Duration.ofMillis(2500).toMillis()); }
            }
            throw new IllegalStateException("问答排队超时，请稍后重试");
        } finally {
            if (!acquired) {
                redis.opsForZSet().remove(QUEUE, ticket);
                redis.opsForHash().delete(QUEUE_TIMES, ticket);
            }
        }
    }

    public int maxConcurrent() { return currentMaxConcurrent(); }

    private int currentMaxConcurrent() {
        String configured = redis.opsForValue().get("intelhub:settings:max-concurrent");
        if (configured == null) return maxConcurrent;
        try { return Math.max(1, Math.min(64, Integer.parseInt(configured))); }
        catch (NumberFormatException ignored) { return maxConcurrent; }
    }

    public void updateMaxConcurrent(int value) {
        if (value < 1 || value > 64) throw new IllegalArgumentException("并发上限需在 1 到 64 之间");
        maxConcurrent = value;
        redis.opsForValue().set("intelhub:settings:max-concurrent", Integer.toString(value));
        redis.convertAndSend(CHANNEL, "settings-updated");
    }

    private void onMessage(Message message, byte[] pattern) {
        synchronized (wakeMonitor) { wakeMonitor.notifyAll(); }
    }

    public final class Lease implements AutoCloseable {
        private final String ticket;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(String ticket) { this.ticket = ticket; }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) return;
            redis.execute(RELEASE, List.of(PERMITS), ticket);
            redis.convertAndSend(CHANNEL, "released");
        }
    }
}
