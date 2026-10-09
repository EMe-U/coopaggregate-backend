package com.coopaggregate.loss;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/lots/{lotId}/losses")
@Tag(name = "Losses", description = "Potatoes lost from a lot (rot, damage, theft)")
@SecurityRequirement(name = "bearerAuth")
public class LossController {

    private final LossService lossService;

    public LossController(LossService lossService) {
        this.lossService = lossService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a loss on an open or closed lot. It cannot be more than the lot's remaining kg")
    public LossResponse record(@PathVariable Long lotId, @Valid @RequestBody LossRequest request) {
        return lossService.record(lotId, request);
    }

    @GetMapping
    @Operation(summary = "List the losses of a lot, newest first")
    public List<LossResponse> list(@PathVariable Long lotId) {
        return lossService.list(lotId);
    }
}
