package com.intelhub.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.intelhub.framework.web.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private JdbcTemplate jdbc;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        controller = new AuthController(jdbc);
    }

    @Test
    void registrationCreatesUserWithBcryptPassword() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        ApiResult<AuthController.AuthView> result = controller.register(
                new AuthController.RegisterRequest("alice", "a-long-test-password", "Alice"));

        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO app_user"), args.capture());
        assertThat(result.data().role()).isEqualTo("USER");
        assertThat(args.getValue()[1]).isEqualTo("alice");
        assertThat(new BCryptPasswordEncoder().matches("a-long-test-password", (String) args.getValue()[2])).isTrue();
    }

    @Test
    void registrationRejectsCaseInsensitiveDuplicateUsername() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        assertThatThrownBy(() -> controller.register(
                new AuthController.RegisterRequest("ALICE", "a-long-test-password", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void loginVerifiesPasswordAndCreatesSession() throws Exception {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> mappedUsers(invocation.getArgument(1), id, "alice",
                        new BCryptPasswordEncoder().encode("a-long-test-password"), "Alice", "USER", true));

        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getTokenValue).thenReturn("test-token");
            ApiResult<AuthController.AuthView> result = controller.login(
                    new AuthController.LoginRequest("alice", "a-long-test-password"));

            assertThat(result.data().id()).isEqualTo(id.toString());
            assertThat(result.data().role()).isEqualTo("USER");
            assertThat(result.data().token()).isEqualTo("test-token");
            session.verify(() -> StpUtil.login(id.toString()));
        }
    }

    @Test
    void loginRejectsIncorrectPassword() throws Exception {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> mappedUsers(invocation.getArgument(1), id, "alice",
                        new BCryptPasswordEncoder().encode("correct-password"), "Alice", "USER", true));

        try (var session = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> controller.login(new AuthController.LoginRequest("alice", "wrong-password")))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
            session.verifyNoInteractions();
        }
    }

    @Test
    void currentUserReturnsSessionIdentityAndRole() throws Exception {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> mappedUsers(invocation.getArgument(1), id, "alice",
                        "unused-hash", "Alice", "ADMIN", true).get(0));

        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsString).thenReturn(id.toString());
            session.when(StpUtil::getTokenValue).thenReturn("current-token");

            ApiResult<AuthController.AuthView> result = controller.me();

            assertThat(result.data().id()).isEqualTo(id.toString());
            assertThat(result.data().role()).isEqualTo("ADMIN");
            assertThat(result.data().token()).isEqualTo("current-token");
            session.verify(StpUtil::checkLogin);
        }
    }

    @Test
    void logoutRevokesSession() {
        try (var session = mockStatic(StpUtil.class)) {
            ApiResult<Void> result = controller.logout();

            assertThat(result.success()).isTrue();
            session.verify(StpUtil::checkLogin);
            session.verify(StpUtil::logout);
        }
    }

    private static List<?> mappedUsers(RowMapper<?> mapper, UUID id, String username, String passwordHash,
                                       String displayName, String role, boolean enabled) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(id);
        when(row.getString("username")).thenReturn(username);
        when(row.getString("password_hash")).thenReturn(passwordHash);
        when(row.getString("display_name")).thenReturn(displayName);
        when(row.getString("role")).thenReturn(role);
        when(row.getBoolean("enabled")).thenReturn(enabled);
        return List.of(mapper.mapRow(row, 0));
    }
}
