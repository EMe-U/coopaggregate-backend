package com.coopaggregate.grade;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** The code is fixed, so it cannot be changed here. */
public record GradeRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 50) String nameRw,
        @NotNull Boolean active,
        @PositiveOrZero Long floorPricePerKg,
        @Size(max = 2000) String description,
        @Size(max = 100) String sizeInfo,
        @Size(max = 100) String packaging) {
}
