package com.coopaggregate.share;

import java.math.BigDecimal;

import com.coopaggregate.lot.LotStatus;

/** The kilograms one member delivered to one lot. */
public record MemberLotKg(Long lotId, String lotCode, LotStatus status, BigDecimal kg) {
}
