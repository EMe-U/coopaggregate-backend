package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The part of a delivery that is in one lot: kg is what the lot got from it, and how much of that was sold, lost and
 * is left. A confirmed delivery has one portion, or two or more when it filled the end of a lot and started the next;
 * a delivery from before fixed-size lots has one, in its own lot. A waiting delivery has none until it is confirmed.
 */
public record PortionResponse(
        Long lotId,
        String lotCode,
        LotStatus lotStatus,
        BigDecimal kg,
        BigDecimal soldKg,
        BigDecimal lostKg,
        BigDecimal remainingKg) {

    public static PortionResponse from(LotPortion portion) {
        Lot lot = portion.getLot();
        return new PortionResponse(lot.getId(), lot.getLotCode(), lot.getStatus(), portion.getQuantityKg(),
                portion.getSoldKg(), portion.getLostKg(), portion.unallocatedKg());
    }

    /** The portions of each delivery, oldest lot first, from one query's rows. */
    public static Map<Long, List<PortionResponse>> byDelivery(Collection<LotPortion> portions) {
        return portions.stream()
                .sorted(java.util.Comparator.comparing((LotPortion portion) -> portion.getLot().getId()))
                .collect(Collectors.groupingBy(portion -> portion.getDelivery().getId(),
                        Collectors.mapping(PortionResponse::from, Collectors.toList())));
    }
}
