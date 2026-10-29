package com.coopaggregate.lot;

import java.math.BigDecimal;

import com.coopaggregate.delivery.ConfirmationStatus;

/** The kg left in the batches of one grade that have one confirmation status. */
public record GradeKgLeft(String gradeCode, String gradeName, ConfirmationStatus status, BigDecimal kg) {
}
