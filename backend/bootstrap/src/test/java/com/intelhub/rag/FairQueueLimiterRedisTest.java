package com.intelhub.rag;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FairQueueLimiterRedisTest {
    private static final String QUEUE = "intelhub:chat:queue";
    private static final String QUEUE_TIMES = "intelhub:chat:queue-times";
    private static Process redisProcess;
    private static Path redisDirectory;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final List<FairQueueLimiter> limiters = new ArrayList<>();

    @BeforeAll
    static void startRedis() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        redisDirectory = Files.createTempDirectory("intelhub-fair-queue-redis-");
        Path log = redisDirectory.resolve("redis.log");
        try {
            redisProcess = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", Integer.toString(port),
                    "--save", "", "--appendonly", "no", "--dir", redisDirectory.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "redis-server is required for the real Redis limiter tests: " + unavailable.getMessage());
            return;
        }

        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        await(() -> {
            if (!redisProcess.isAlive()) throw new IllegalStateException("redis-server exited during startup");
            try (RedisConnection connection = connectionFactory.getConnection()) {
                return "PONG".equals(connection.ping());
            }
        }, Duration.ofSeconds(10), "redis-server did not become ready");
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (connectionFactory != null) connectionFactory.destroy();
        if (redisProcess != null && redisProcess.isAlive()) {
            redisProcess.destroy();
            if (!redisProcess.waitFor(3, TimeUnit.SECONDS)) redisProcess.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
        }
        if (redisDirectory != null) {
            try (var paths = Files.walk(redisDirectory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        }
    }

    @BeforeEach
    void clearRedisAndStartListeners() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @AfterEach
    void stopListeners() {
        limiters.forEach(FairQueueLimiter::stopListener);
        limiters.clear();
    }

    @Test
    void grantsTheSinglePermitInFifoOrderAcrossTwoLimiterInstances() throws Exception {
        FairQueueLimiter firstInstance = limiter(1, 12, 12);
        FairQueueLimiter secondInstance = limiter(1, 12, 12);
        FairQueueLimiter.Lease occupyingLease = firstInstance.acquire(UUID.randomUUID(), new AtomicBoolean(), ignored -> { });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        List<String> admissionOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        Future<?> firstWaiter = null;
        Future<?> secondWaiter = null;
        try {
            firstWaiter = workers.submit(() -> acquireAndHold(firstInstance, "first", admissionOrder, firstAcquired, releaseFirst));
            awaitQueueSize(1, Duration.ofSeconds(3));
            secondWaiter = workers.submit(() -> acquireAndHold(secondInstance, "second", admissionOrder, secondAcquired, releaseSecond));
            awaitQueueSize(2, Duration.ofSeconds(3));

            occupyingLease.close();
            assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(admissionOrder).containsExactly("first");
            assertThat(secondAcquired.await(300, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            assertThat(secondAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(admissionOrder).containsExactly("first", "second");
            releaseSecond.countDown();
            firstWaiter.get(3, TimeUnit.SECONDS);
            secondWaiter.get(3, TimeUnit.SECONDS);
            assertThat(redis.opsForZSet().zCard(QUEUE)).isZero();
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            occupyingLease.close();
            workers.shutdownNow();
        }
    }

    @Test
    void expiresLeasesAndAllowsTheNextInstanceToAcquireWithoutReleasingTheOldLease() throws Exception {
        FairQueueLimiter firstInstance = limiter(1, 8, 1);
        FairQueueLimiter secondInstance = limiter(1, 8, 10);
        FairQueueLimiter.Lease oldLease = firstInstance.acquire(UUID.randomUUID(), new AtomicBoolean(), ignored -> { });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch secondAcquired = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        Future<?> secondWaiter = worker.submit(() -> acquireAndHold(secondInstance, "second", new ArrayList<>(), secondAcquired, releaseSecond));
        try {
            awaitQueueSize(1, Duration.ofSeconds(3));
            assertThat(secondAcquired.await(6, TimeUnit.SECONDS)).isTrue();
            assertThat(redis.opsForZSet().zCard("intelhub:chat:permits")).isEqualTo(1);
            oldLease.close();
            assertThat(redis.opsForZSet().zCard("intelhub:chat:permits")).isEqualTo(1);
            releaseSecond.countDown();
            secondWaiter.get(3, TimeUnit.SECONDS);
        } finally {
            oldLease.close();
            releaseSecond.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void enforcesPerUserMinuteQuotaAndRemovesRequestsThatTimeOut() throws Exception {
        FairQueueLimiter limiter = limiter(32, 1, 10);
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            try (FairQueueLimiter.Lease ignored = limiter.acquire(user, new AtomicBoolean(), position -> { })) { }
        }
        assertThatThrownBy(() -> limiter.acquire(user, new AtomicBoolean(), position -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("20");

        FairQueueLimiter timeoutLimiter = limiter(1, 1, 10);
        FairQueueLimiter.Lease occupyingLease = timeoutLimiter.acquire(UUID.randomUUID(), new AtomicBoolean(), position -> { });
        try {
            assertThatThrownBy(() -> timeoutLimiter.acquire(UUID.randomUUID(), new AtomicBoolean(), position -> { }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(redis.opsForZSet().zCard(QUEUE)).isZero();
            assertThat(redis.opsForHash().size(QUEUE_TIMES)).isZero();
        } finally {
            occupyingLease.close();
        }
    }

    @Test
    void cancellationWhileQueuedRemovesItsTicket() throws Exception {
        FairQueueLimiter limiter = limiter(1, 8, 10);
        FairQueueLimiter.Lease occupyingLease = limiter.acquire(UUID.randomUUID(), new AtomicBoolean(), ignored -> { });
        AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<String> waiting = worker.submit(() -> {
            try (FairQueueLimiter.Lease ignored = limiter.acquire(UUID.randomUUID(), cancelled, position -> { })) {
                return "acquired";
            } catch (InterruptedException expected) {
                return "interrupted";
            }
        });
        try {
            awaitQueueSize(1, Duration.ofSeconds(3));
            cancelled.set(true);
            assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("interrupted");
            assertThat(redis.opsForZSet().zCard(QUEUE)).isZero();
            assertThat(redis.opsForHash().size(QUEUE_TIMES)).isZero();
        } finally {
            occupyingLease.close();
            worker.shutdownNow();
        }
    }

    private FairQueueLimiter limiter(int maxConcurrent, int maxWaitSeconds, int leaseSeconds) {
        FairQueueLimiter limiter = new FairQueueLimiter(redis, connectionFactory, maxConcurrent, maxWaitSeconds, leaseSeconds);
        limiter.subscribeToReleaseEvents();
        limiters.add(limiter);
        return limiter;
    }

    private static void acquireAndHold(FairQueueLimiter limiter, String name, List<String> order,
                                       CountDownLatch acquired, CountDownLatch release) {
        try (FairQueueLimiter.Lease ignored = limiter.acquire(UUID.randomUUID(), new AtomicBoolean(), position -> { })) {
            order.add(name);
            acquired.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lease hold timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void awaitQueueSize(long expected, Duration timeout) throws Exception {
        await(() -> expected == redis.opsForZSet().zCard(QUEUE), timeout, "queue did not reach size " + expected);
    }

    private static void await(BooleanSupplier condition, Duration timeout, String message) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError(message);
    }
}
