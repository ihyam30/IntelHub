package com.intelhub.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final String username;
    private final String password;

    public AdminBootstrap(JdbcTemplate jdbc,
                          @Value("${INTELHUB_ADMIN_USERNAME:}") String username,
                          @Value("${INTELHUB_ADMIN_PASSWORD:}") String password) {
        this.jdbc = jdbc;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank()) return;
        Integer count = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE lower(username)=lower(?)", Integer.class, username);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name, role) VALUES (?, ?, ?, ?, 'ADMIN') ON CONFLICT (username) DO NOTHING",
                UUID.randomUUID(), username, new BCryptPasswordEncoder().encode(password), "IntelHub 管理员");
    }
}
