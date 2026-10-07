package com.intelhub.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    @Test
    void doesNotCreateAdminWhenCredentialsAreMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new AdminBootstrap(jdbc, "", "").run(mock(ApplicationArguments.class));

        verifyNoInteractions(jdbc);
    }

    @Test
    void createsAdminWithBcryptPasswordWhenConfiguredAndUserDoesNotExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        new AdminBootstrap(jdbc, "admin-demo", "a-long-admin-password").run(mock(ApplicationArguments.class));

        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("'ADMIN'"), args.capture());
        assertThat(args.getValue()[1]).isEqualTo("admin-demo");
        assertThat(new BCryptPasswordEncoder().matches("a-long-admin-password", (String) args.getValue()[2])).isTrue();
    }

    @Test
    void doesNotRecreateExistingAdmin() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        new AdminBootstrap(jdbc, "admin-demo", "a-long-admin-password").run(mock(ApplicationArguments.class));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
