package com.coopaggregate.publicapi;

import com.coopaggregate.grade.Grade;

/**
 * A grade as buyers see it. Built field by field so that nothing else is ever shown: no id, no floor price and no
 * stock numbers, only the availability hint. The code (A, B, C) is what a purchase request refers to.
 */
public record PublicGradeResponse(
        String code,
        String name,
        String nameRw,
        String description,
        String sizeInfo,
        String packaging,
        StockAvailability availability) {

    public static PublicGradeResponse from(Grade grade, StockAvailability availability) {
        return new PublicGradeResponse(grade.getCode(), grade.getName(), grade.getNameRw(), grade.getDescription(),
                grade.getSizeDescription(), grade.getPackaging(), availability);
    }
}
