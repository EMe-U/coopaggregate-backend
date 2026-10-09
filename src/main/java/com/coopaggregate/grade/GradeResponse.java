package com.coopaggregate.grade;

public record GradeResponse(Long id, String code, String name) {

    public static GradeResponse from(Grade grade) {
        return new GradeResponse(grade.getId(), grade.getCode(), grade.getName());
    }
}
