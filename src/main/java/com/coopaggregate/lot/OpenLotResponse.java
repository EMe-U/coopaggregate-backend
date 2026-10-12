package com.coopaggregate.lot;

import java.math.BigDecimal;

public record OpenLotResponse(Long id, String code, String gradeName, BigDecimal totalKg) {
}
