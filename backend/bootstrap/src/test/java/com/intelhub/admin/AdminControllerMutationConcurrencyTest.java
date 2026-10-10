package com.intelhub.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.intelhub.ai.ChatModelRouter;
import com.intelhub.ai.EmbeddingService;
import com.intelhub.ai.RerankService;
import com.intelhub.common.CurrentUser;
import com.intelhub.mcp.McpToolClient;
import com.intelhub.rag.FairQueueLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminControllerMutationConcurrencyTest {
    @Test
    void concurrentDemotionCannotRemoveEveryEnabledAdministrator() throws Exception {
        assertOneConcurrentMutationIsRejected(Mutation.DEMOTE);
    }

    @Test
    void concurrentDeletionCannotRemoveEveryEnabledAdministrator() throws Exception {
        assertOneConcurrentMutationIsRejected(Mutation.DELETE);
    }

    @Test
    void concurrentDisablingCannotRemoveEveryEnabledAdministrator() throws Exception {
        assertOneConcurrentMutationIsRejected(Mutation.DISABLE);
    }

    private void assertOneConcurrentMutationIsRejected(Mutation mutation) throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        InMemoryUsers users = new InMemoryUsers(firstId, secondId);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReentrantLock simulatedDatabaseAdvisoryLock = new ReentrantLock(true);
        stubDatabase(jdbc, users, simulatedDatabaseAdvisoryLock);

        // Separate guards model requests handled by separate backend instances.
        AdminController firstController = controller(jdbc, new AdminMutationGuard(jdbc), firstId);
        AdminController secondController = controller(jdbc, new AdminMutationGuard(jdbc), secondId);
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try (var saToken = Mockito.mockStatic(StpUtil.class)) {
            Future<Boolean> first = workers.submit(() -> runAfterBarrier(start,
                    () -> mutate(firstController, secondId, mutation), simulatedDatabaseAdvisoryLock));
            Future<Boolean> second = workers.submit(() -> runAfterBarrier(start,
                    () -> mutate(secondController, firstId, mutation), simulatedDatabaseAdvisoryLock));
            start.await();

            assertThat(first.get()).isNotEqualTo(second.get());
            assertThat(users.enabledAdminCount()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    private static boolean runAfterBarrier(CyclicBarrier barrier, MutationAction action, ReentrantLock databaseLock) {
        try {
            barrier.await();
            action.run();
            return true;
        } catch (ResponseStatusException expected) {
            return false;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            if (databaseLock.isHeldByCurrentThread()) databaseLock.unlock();
        }
    }

    private static void mutate(AdminController controller, UUID targetId, Mutation mutation) {
        switch (mutation) {
            case DEMOTE -> controller.updateUser(targetId, new AdminController.UpdateUserRequest("Admin", "USER"));
            case DELETE -> controller.deleteUser(targetId);
            case DISABLE -> controller.setUserEnabled(targetId, new AdminController.EnabledRequest(false));
        }
    }

    private static AdminController controller(JdbcTemplate jdbc, AdminMutationGuard guard, UUID operatorId) {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(operatorId);
        doNothing().when(currentUser).requireAdmin();
        return new AdminController(jdbc, currentUser, mock(ChatModelRouter.class), mock(EmbeddingService.class),
                mock(FairQueueLimiter.class), mock(StringRedisTemplate.class), mock(McpToolClient.class),
                mock(RerankService.class), guard);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubDatabase(JdbcTemplate jdbc, InMemoryUsers users, ReentrantLock databaseLock) throws Exception {
        doAnswer(invocation -> {
            databaseLock.lock();
            return null;
        }).when(jdbc).execute(contains("pg_advisory_xact_lock"));

        doAnswer(invocation -> {
            RowMapper<AdminController.UserStatus> mapper = invocation.getArgument(1);
            UUID id = (UUID) argument(invocation, 2);
            InMemoryUsers.User user = users.get(id);
            if (user == null) return List.of();
            ResultSet row = mock(ResultSet.class);
            when(row.getString("role")).thenReturn(user.role());
            when(row.getBoolean("enabled")).thenReturn(user.enabled());
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(contains("SELECT role,enabled FROM app_user"), any(RowMapper.class), any(Object[].class));

        doAnswer(invocation -> users.enabledAdminCount())
                .when(jdbc).queryForObject(contains("count(*) FROM app_user WHERE role='ADMIN'"), eq(Integer.class));

        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            UUID id;
            if (sql.contains("password_hash='!deleted!'")) {
                id = (UUID) argument(invocation, 2);
                users.delete(id);
            } else if (sql.contains("SET enabled=?")) {
                id = (UUID) argument(invocation, 2);
                users.setEnabled(id, (Boolean) argument(invocation, 1));
            } else {
                id = (UUID) argument(invocation, 3);
                users.setRole(id, (String) argument(invocation, 2));
            }
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));

        doAnswer(invocation -> {
            RowMapper<AdminController.UserView> mapper = invocation.getArgument(1);
            UUID id = (UUID) argument(invocation, 2);
            InMemoryUsers.User user = users.get(id);
            ResultSet row = mock(ResultSet.class);
            when(row.getObject("id", UUID.class)).thenReturn(id);
            when(row.getString("username")).thenReturn(user.username());
            when(row.getString("display_name")).thenReturn(user.displayName());
            when(row.getString("role")).thenReturn(user.role());
            when(row.getBoolean("enabled")).thenReturn(user.enabled());
            when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(user.createdAt());
            return mapper.mapRow(row, 0);
        }).when(jdbc).queryForObject(contains("SELECT id,username,display_name,role,enabled,created_at"),
                any(RowMapper.class), any(Object[].class));
    }

    private static Object argument(InvocationOnMock invocation, int index) {
        Object value = invocation.getArgument(index);
        if (value instanceof Object[] values && values.length == 1) return values[0];
        return value;
    }

    private enum Mutation { DEMOTE, DELETE, DISABLE }

    @FunctionalInterface
    private interface MutationAction {
        void run();
    }

    private static final class InMemoryUsers {
        private final Map<UUID, User> users;

        private InMemoryUsers(UUID firstId, UUID secondId) {
            OffsetDateTime createdAt = OffsetDateTime.now();
            users = new java.util.concurrent.ConcurrentHashMap<>();
            users.put(firstId, new User("admin-a", "Admin A", "ADMIN", true, createdAt));
            users.put(secondId, new User("admin-b", "Admin B", "ADMIN", true, createdAt));
        }

        private synchronized User get(UUID id) {
            return users.get(id);
        }

        private synchronized int enabledAdminCount() {
            return (int) users.values().stream().filter(user -> "ADMIN".equals(user.role()) && user.enabled()).count();
        }

        private synchronized void setRole(UUID id, String role) {
            User user = users.get(id);
            users.put(id, user.withRole(role));
        }

        private synchronized void setEnabled(UUID id, boolean enabled) {
            User user = users.get(id);
            users.put(id, user.withEnabled(enabled));
        }

        private synchronized void delete(UUID id) {
            User user = users.get(id);
            users.put(id, user.withRole("USER").withEnabled(false));
        }

        private record User(String username, String displayName, String role, boolean enabled, OffsetDateTime createdAt) {
            private User withRole(String nextRole) {
                return new User(username, displayName, nextRole, enabled, createdAt);
            }

            private User withEnabled(boolean nextEnabled) {
                return new User(username, displayName, role, nextEnabled, createdAt);
            }
        }
    }
}
