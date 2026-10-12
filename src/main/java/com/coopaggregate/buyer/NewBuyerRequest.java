package com.coopaggregate.buyer;

import com.coopaggregate.common.RwandanPhoneNumber;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Named NewBuyerRequest because BuyerRequest is the entity for a buyer's request to purchase.
public record NewBuyerRequest(
        @NotBlank(message = "is required")
        @Size(max = 150, message = "must be at most 150 characters")
        String name,

        @NotBlank(message = "is required")
        @Pattern(regexp = RwandanPhoneNumber.PATTERN,
                message = "must be a Rwandan mobile number: 07XXXXXXXX, 2507XXXXXXXX or +2507XXXXXXXX")
        String phone,

        @Size(max = 30, message = "must be at most 30 characters")
        String type) {
}
