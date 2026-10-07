package com.intelhub.common;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CurrentUser {
    private final JdbcTemplate jdbc;

    public CurrentUser(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID id() {
        StpUtil.checkLogin();
        UUID id = UUID.fromString(StpUtil.getLoginIdAsString());
        Integer active = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id=? AND enabled=TRUE", Integer.class, id);
        if (active == null || active == 0) {
            StpUtil.logout();
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "账号已停用");
        }
        return id;
    }

    public void requireAdmin() {
        if (!isAdmin()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "需要管理员权限");
    }

    public boolean isAdmin() {
        UUID id = id();
        String role = jdbc.queryForObject("SELECT role FROM app_user WHERE id = ? AND enabled = TRUE", String.class, id);
        return "ADMIN".equals(role);
    }
}
