package com.coopaggregate.publicapi;

import com.coopaggregate.grade.Grade;

/**
 * A grade as buyers see it. Built field by field so that nothing else is ever shown: no id and no floor price.
 * The code (A, B, C) is what a purchase request refers to.
 */
public record PublicGradeResponse(
        String code,
        String name,
        String nameRw,
        String description,
        String sizeInfo,
        String packaging) {

    public static PublicGradeResponse from(Grade grade) {
        return new PublicGradeResponse(grade.getCode(), grade.getName(), grade.getNameRw(), grade.getDescription(),
                grade.getSizeDescription(), grade.getPackaging());
    }
}
