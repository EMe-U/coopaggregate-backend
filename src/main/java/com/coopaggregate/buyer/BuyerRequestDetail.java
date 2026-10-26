package com.coopaggregate.buyer;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One purchase request for the manager. publicMessage is what the buyer can read; buyerId is set once the manager
 * has turned the request into a buyer record.
 */
public record BuyerRequestDetail(
        Long id,
        String code,
        BuyerRequestStatus status,
        String gradeCode,
        String gradeName,
        BigDecimal quantityKg,
        String buyerName,
        String buyerPhone,
        String buyerEmail,
        String note,
        String publicMessage,
        Long buyerId,
        Instant createdAt,
        Instant updatedAt) {

    static BuyerRequestDetail from(BuyerRequest request) {
        Buyer buyer = request.getBuyer();
        boolean contactOnRequest = request.getRequesterName() != null || buyer == null;
        return new BuyerRequestDetail(request.getId(), request.getReferenceCode(), request.getStatus(),
                request.getGrade().getCode(), request.getGrade().getName(), request.getQuantityKg(),
                contactOnRequest ? request.getRequesterName() : buyer.getName(),
                contactOnRequest ? request.getRequesterPhone() : buyer.getPhone(),
                contactOnRequest ? request.getRequesterEmail() : buyer.getEmail(),
                request.getMessage(), request.getPublicMessage(), buyer == null ? null : buyer.getId(),
                request.getCreatedAt(), request.getUpdatedAt());
    }
}
