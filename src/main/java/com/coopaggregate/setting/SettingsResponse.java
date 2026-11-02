package com.coopaggregate.setting;

import java.math.BigDecimal;
import java.util.List;

/**
 * deductionPerKg is shown for information; it is not changed through the settings page. lotSizeLocked is true once the
 * first fixed-size lot exists: from then on the lot size can no longer change.
 */
public record SettingsResponse(
        boolean smsEnabled,
        String cooperativeEmail,
        String location,
        BigDecimal minOrderKg,
        List<String> committeeEmails,
        Long deductionPerKg,
        String cooperativeName,
        String cooperativePhone,
        BigDecimal lotSizeKg,
        boolean lotSizeLocked) {

    public static SettingsResponse from(Setting setting, boolean lotSizeLocked) {
        return new SettingsResponse(setting.isSmsEnabled(), setting.getCooperativeEmail(), setting.getLocation(),
                setting.getMinOrderKg(), setting.getCommitteeEmailList(), setting.getDeductionPerKg(),
                setting.getCooperativeName(),
                setting.getCooperativePhone(),
                setting.getLotSizeKg(),
                lotSizeLocked);
    }
}
