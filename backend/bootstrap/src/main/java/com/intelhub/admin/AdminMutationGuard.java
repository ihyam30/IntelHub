package com.intelhub.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes account mutations that can change the set of enabled administrators.
 * The PostgreSQL transaction lock coordinates multiple backend instances; the local
 * lock also keeps same-instance requests in a deterministic order.
 */
@Component
public class AdminMutationGuard {
    private static final String DATABASE_LOCK_SQL = "SELECT pg_advisory_xact_lock(1229872204, 4744354)";

    private final JdbcTemplate jdbc;
    private final ReentrantLock localLock = new ReentrantLock(true);

    public AdminMutationGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public <T> T withLock(Supplier<T> action) {
        localLock.lock();
        try {
            jdbc.execute(DATABASE_LOCK_SQL);
            return action.get();
        } finally {
            localLock.unlock();
        }
    }
}
