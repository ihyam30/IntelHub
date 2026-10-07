package com.intelhub.rag;

import com.intelhub.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceControllerTest {
    private static final UUID OWNER = UUID.fromString("1f2b9f47-947d-45cd-a5da-420d28b607c3");

    @Test
    void userTraceQueryIsOwnerScopedAndClampsThePageSize() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<Object[]> parameters = new AtomicReference<>();
        when(currentUser.id()).thenReturn(OWNER);
        when(currentUser.isAdmin()).thenReturn(false);
        doAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            parameters.set(Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length));
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        new TraceController(jdbc, currentUser).list(500);

        assertThat(sql.get()).contains("WHERE t.owner_id=?");
        assertThat(parameters.get()).containsExactly(OWNER, 200);
    }

    @Test
    void adminTraceQueryCanViewAllUsersAndClampsThePageSize() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<Object[]> parameters = new AtomicReference<>();
        when(currentUser.id()).thenReturn(OWNER);
        when(currentUser.isAdmin()).thenReturn(true);
        doAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            parameters.set(Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length));
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        new TraceController(jdbc, currentUser).list(0);

        assertThat(sql.get()).doesNotContain("WHERE t.owner_id=?");
        assertThat(parameters.get()).containsExactly(1);
    }
}
