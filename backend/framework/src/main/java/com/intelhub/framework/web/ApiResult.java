package com.intelhub.framework.web;

public record ApiResult<T>(boolean success, T data, String message) {
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, data, "ok");
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(true, null, "ok");
    }

    public static ApiResult<Void> error(String message) {
        return new ApiResult<>(false, null, message);
    }
}
