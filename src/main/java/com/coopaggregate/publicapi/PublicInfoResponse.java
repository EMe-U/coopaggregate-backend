package com.coopaggregate.publicapi;

import java.math.BigDecimal;

import com.coopaggregate.setting.Setting;

/** What buyers may know about the cooperative. Nothing about members, stock, prices or the ledger. */
public record PublicInfoResponse(
        String cooperativeName,
        String location,
        String cooperativeEmail,
        BigDecimal minOrderKg,
        String cooperativePhone) {

    public static PublicInfoResponse from(Setting setting) {
        return new PublicInfoResponse(setting.getCooperativeName(), setting.getLocation(),
                setting.getCooperativeEmail(), setting.getMinOrderKg(), setting.getCooperativePhone());
    }
}
