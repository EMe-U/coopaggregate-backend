package com.coopaggregate.batch;

/** Money credited by batch: gross, the deduction, and what the member earned (gross minus deduction). */
public record BatchEarnings(long grossRwf, long deductionRwf, long earnedRwf) {

    public static final BatchEarnings NONE = new BatchEarnings(0, 0, 0);

    public BatchEarnings(Long grossRwf, Long deductionRwf) {
        this(grossRwf, deductionRwf, grossRwf - deductionRwf);
    }
}
