package com.coopaggregate.setting;

import java.math.BigDecimal;
import java.util.List;

/** deductionPerKg is shown for information; it is not changed through the settings page. */
public record SettingsResponse(
        boolean smsEnabled,
        String cooperativeEmail,
        String location,
        BigDecimal minOrderKg,
        List<String> committeeEmails,
        Long deductionPerKg) {

    public static SettingsResponse from(Setting setting) {
        return new SettingsResponse(setting.isSmsEnabled(), setting.getCooperativeEmail(), setting.getLocation(),
                setting.getMinOrderKg(), setting.getCommitteeEmailList(), setting.getDeductionPerKg());
    }
}
