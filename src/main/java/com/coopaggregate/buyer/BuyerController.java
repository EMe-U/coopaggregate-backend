package com.coopaggregate.buyer;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/buyers")
@Tag(name = "Buyers", description = "People and businesses that buy potatoes from the cooperative")
@SecurityRequirement(name = "bearerAuth")
public class BuyerController {

    private final BuyerService buyerService;

    public BuyerController(BuyerService buyerService) {
        this.buyerService = buyerService;
    }

    @GetMapping
    @Operation(summary = "List buyers sorted by name")
    public List<BuyerResponse> list(
            @Parameter(description = "Part of the name or phone number (case-insensitive)")
            @RequestParam(required = false) String search) {
        return buyerService.list(search);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a buyer. The phone number must not belong to another buyer")
    public BuyerResponse create(@Valid @RequestBody NewBuyerRequest request) {
        return buyerService.create(request);
    }
}
