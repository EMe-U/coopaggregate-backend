package com.coopaggregate.lot;

import java.math.BigDecimal;

/** The lot being filled for a grade: totalKg is what it holds so far, capacityKg its fixed size. */
public record OpenLotResponse(Long id, String code, String gradeName, BigDecimal totalKg, BigDecimal capacityKg) {
}
