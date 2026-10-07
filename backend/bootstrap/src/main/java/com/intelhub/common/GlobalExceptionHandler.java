package com.intelhub.common;

import cn.dev33.satoken.exception.NotLoginException;
import com.intelhub.framework.web.ApiResult;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(NotLoginException.class)
    ResponseEntity<ApiResult<Void>> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.error("请先登录"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResult<Void>> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ApiResult.error(ex.getReason() == null ? "请求失败" : ex.getReason()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiResult<Void>> validation(Exception ex) {
        String message = ex instanceof MethodArgumentNotValidException validation && validation.getBindingResult().getFieldError() != null
                ? validation.getBindingResult().getFieldError().getDefaultMessage() : "请求参数不合法";
        return ResponseEntity.badRequest().body(ApiResult.error(message));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiResult<Void>> duplicate() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResult.error("用户名或资源已存在"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResult<Void>> unexpected(Exception ex) {
        return ResponseEntity.internalServerError().body(ApiResult.error("服务暂时不可用"));
    }
}
