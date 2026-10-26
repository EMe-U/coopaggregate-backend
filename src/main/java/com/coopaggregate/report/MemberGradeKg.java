package com.coopaggregate.report;

import java.math.BigDecimal;

/** Kilograms one member delivered of one grade in a week. */
public record MemberGradeKg(Long memberId, String memberNumber, String memberName, String gradeName, BigDecimal kg) {
}
