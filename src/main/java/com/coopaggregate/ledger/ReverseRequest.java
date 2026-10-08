package com.coopaggregate.ledger;

import jakarta.validation.constraints.NotBlank;

public record ReverseRequest(@NotBlank String reason) {
}
