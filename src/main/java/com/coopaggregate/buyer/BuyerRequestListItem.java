package com.coopaggregate.buyer;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A purchase request in the manager's list. contactMethod is how the buyer wants to be contacted; whatsappNumber is the
 * number to use for WHATSAPP and empty for CALL (then buyerPhone is the number to call).
 */
public record BuyerRequestListItem(
        Long id,
        String code,
        BuyerRequestStatus status,
        String gradeName,
        BigDecimal quantityKg,
        String buyerName,
        String buyerPhone,
        BuyerContactMethod contactMethod,
        String whatsappNumber,
        boolean converted,
        Instant createdAt) {

    static BuyerRequestListItem from(BuyerRequest request) {
        Buyer buyer = request.getBuyer();
        return new BuyerRequestListItem(request.getId(), request.getReferenceCode(), request.getStatus(),
                request.getGrade().getName(), request.getQuantityKg(),
                request.getRequesterName() != null || buyer == null ? request.getRequesterName() : buyer.getName(),
                request.getRequesterPhone() != null || buyer == null ? request.getRequesterPhone() : buyer.getPhone(),
                request.getContactMethod(), request.getWhatsappNumber(), buyer != null, request.getCreatedAt());
    }
}
