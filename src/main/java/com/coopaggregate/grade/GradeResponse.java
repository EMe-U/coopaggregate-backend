package com.coopaggregate.grade;

public record GradeResponse(
        Long id,
        String code,
        String name,
        String nameRw,
        boolean active,
        Long floorPricePerKg,
        String description,
        String sizeInfo,
        String packaging) {

    public static GradeResponse from(Grade grade) {
        return new GradeResponse(grade.getId(), grade.getCode(), grade.getName(), grade.getNameRw(),
                grade.isActive(), grade.getFloorPricePerKg(), grade.getDescription(), grade.getSizeDescription(),
                grade.getPackaging());
    }
}
