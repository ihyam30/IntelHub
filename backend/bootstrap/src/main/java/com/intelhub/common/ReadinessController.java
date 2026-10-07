package com.intelhub.common;

import com.intelhub.framework.web.ApiResult;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
public class ReadinessController {
    private final ApplicationAvailability availability;

    public ReadinessController(ApplicationAvailability availability) {
        this.availability = availability;
    }

    @GetMapping("/ready")
    public ResponseEntity<ApiResult<Map<String, String>>> readiness() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiResult<>(false, Map.of("status", "STARTING"), "服务正在启动"));
        }
        return ResponseEntity.ok(ApiResult.ok(Map.of("status", "UP")));
    }
}
