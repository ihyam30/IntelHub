package com.intelhub.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.intelhub.framework.web.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResult<AuthView> register(@Valid @RequestBody RegisterRequest request) {
        if (jdbc.queryForObject("SELECT count(*) FROM app_user WHERE lower(username)=lower(?)", Integer.class, request.username()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已被使用");
        }
        UUID id = UUID.randomUUID();
        String displayName = request.displayName() == null || request.displayName().isBlank() ? request.username() : request.displayName().trim();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name, role) VALUES (?, ?, ?, ?, 'USER')",
                id, request.username().trim(), passwordEncoder.encode(request.password()), displayName);
        return ApiResult.ok(view(id, request.username().trim(), displayName, "USER", null));
    }

    @PostMapping("/login")
    ApiResult<AuthView> login(@Valid @RequestBody LoginRequest request) {
        var users = jdbc.query("SELECT id, username, password_hash, display_name, role, enabled FROM app_user WHERE lower(username)=lower(?)",
                (rs, row) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("display_name"), rs.getString("role"), rs.getBoolean("enabled")), request.username());
        if (users.isEmpty() || !users.get(0).enabled() || !passwordEncoder.matches(request.password(), users.get(0).passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        UserRow user = users.get(0);
        StpUtil.login(user.id().toString());
        return ApiResult.ok(view(user.id(), user.username(), user.displayName(), user.role(), StpUtil.getTokenValue()));
    }

    @PostMapping("/logout")
    ApiResult<Void> logout() {
        StpUtil.checkLogin();
        StpUtil.logout();
        return ApiResult.ok();
    }

    @GetMapping("/me")
    ApiResult<AuthView> me() {
        StpUtil.checkLogin();
        UUID id = UUID.fromString(StpUtil.getLoginIdAsString());
        UserRow user = jdbc.queryForObject("SELECT id, username, password_hash, display_name, role, enabled FROM app_user WHERE id = ?",
                (rs, row) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("display_name"), rs.getString("role"), rs.getBoolean("enabled")), id);
        if (user == null || !user.enabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已停用");
        return ApiResult.ok(view(id, user.username(), user.displayName(), user.role(), StpUtil.getTokenValue()));
    }

    private AuthView view(UUID id, String username, String name, String role, String token) {
        return new AuthView(id.toString(), username, name, role, token);
    }

    public record RegisterRequest(@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_.-]{3,64}$", message = "用户名需为 3-64 位字母、数字或 ._-") String username,
                                  @NotBlank @Size(min = 10, max = 128, message = "密码长度需为 10-128 位") String password,
                                  @Size(max = 100) String displayName) {}
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record AuthView(String id, String username, String displayName, String role, String token) {}
    private record UserRow(UUID id, String username, String passwordHash, String displayName, String role, boolean enabled) {}
}
