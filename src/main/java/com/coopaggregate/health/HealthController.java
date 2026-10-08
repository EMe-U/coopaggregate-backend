package com.coopaggregate.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/health")
@Tag(name = "Health", description = "Service status")
public class HealthController {

    @GetMapping
    @Operation(summary = "Check that the API is running")
    public Map<String, String> health() {
        return Map.of("status", "OK");
    }
}
