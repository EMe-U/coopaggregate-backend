package com.coopaggregate.loss;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record LossResponse(
        Long id,
        Long lotId,
        String lotCode,
        BigDecimal quantityKg,
        LossReason reason,
        String notes,
        LocalDate recordedAt,
        Instant createdAt) {

    public static LossResponse from(Loss loss) {
        return new LossResponse(
                loss.getId(),
                loss.getLot().getId(),
                loss.getLot().getLotCode(),
                loss.getQuantityKg(),
                loss.getReason(),
                loss.getNotes(),
                loss.getLossDate(),
                loss.getCreatedAt());
    }
}
