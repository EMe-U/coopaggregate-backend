package com.coopaggregate.setting;

import java.math.BigDecimal;
import java.util.List;

/**
 * deductionPerKg is shown for information; it is not changed through the settings page.
 * identityVerificationAvailable says whether the identity provider is switched on and set up.
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
        boolean requireIdentityVerification,
        boolean identityVerificationAvailable) {

    public static SettingsResponse from(Setting setting, boolean identityVerificationAvailable) {
        return new SettingsResponse(setting.isSmsEnabled(), setting.getCooperativeEmail(), setting.getLocation(),
                setting.getMinOrderKg(), setting.getCommitteeEmailList(), setting.getDeductionPerKg(),
                setting.getCooperativeName(),
                setting.getCooperativePhone(), setting.isRequireIdentityVerification(),
                identityVerificationAvailable);
    }
}
