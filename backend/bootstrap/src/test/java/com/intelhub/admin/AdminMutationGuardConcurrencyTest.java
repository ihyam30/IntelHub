package com.intelhub.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AdminMutationGuardConcurrencyTest {
    @Test
    void serializesConcurrentAdminMutationsAndUsesDatabaseWideLock() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminMutationGuard guard = new AdminMutationGuard(jdbc);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger activeMutations = new AtomicInteger();
        AtomicInteger maximumConcurrentMutations = new AtomicInteger();

        try {
            Future<?> first = workers.submit(() -> guard.withLock(() -> {
                updateMaximum(maximumConcurrentMutations, activeMutations.incrementAndGet());
                firstEntered.countDown();
                await(releaseFirst);
                activeMutations.decrementAndGet();
                return null;
            }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = workers.submit(() -> {
                secondAttempted.countDown();
                return guard.withLock(() -> {
                    updateMaximum(maximumConcurrentMutations, activeMutations.incrementAndGet());
                    secondEntered.countDown();
                    activeMutations.decrementAndGet();
                    return null;
                });
            });
            assertThat(secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(maximumConcurrentMutations).hasValue(1);
            verify(jdbc, times(2)).execute(contains("pg_advisory_xact_lock"));
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    private static void updateMaximum(AtomicInteger maximum, int value) {
        maximum.updateAndGet(previous -> Math.max(previous, value));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to release admin mutation");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
