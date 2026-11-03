package com.coopaggregate.setting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.audit.ChangeList;
import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;

@Service
public class SettingService {

    private static final int MIN_REASON = 10;
    private static final int MAX_REASON = 500;

    private final SettingRepository settingRepository;
    private final AuditLogRepository auditLogRepository;

    public SettingService(SettingRepository settingRepository, AuditLogRepository auditLogRepository) {
        this.settingRepository = settingRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get() {
        return SettingsResponse.from(setting());
    }

    /** Saves the settings and writes the fields that changed to the change history. */
    @Transactional
    public SettingsResponse update(SettingsRequest request, Manager manager) {
        Setting setting = setting();
        String cooperativeEmail = blankToNull(request.cooperativeEmail());
        String location = blankToNull(request.location());
        List<String> committeeEmails = request.committeeEmails().stream()
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();

        String cooperativeName = blankToNull(request.cooperativeName()) != null
                ? blankToNull(request.cooperativeName())
                : setting.getCooperativeName();

        String cooperativePhone = blankToNull(request.cooperativePhone()) == null
                ? null
                : RwandanPhoneNumber.normalize(blankToNull(request.cooperativePhone()));

        // The lot size can change at any time, with a reason. It only applies to lots opened afterwards: every lot keeps
        // the capacity it was opened with, so the lot being filled keeps its size and the next one gets the new size.
        BigDecimal oldLotSizeKg = setting.getLotSizeKg();
        BigDecimal lotSizeKg = request.lotSizeKg() == null ? oldLotSizeKg : request.lotSizeKg();
        boolean lotSizeChanged = lotSizeKg.compareTo(oldLotSizeKg) != 0;
        String lotSizeReason = blankToNull(request.lotSizeReason());
        if (lotSizeChanged && (lotSizeReason == null || lotSizeReason.length() < MIN_REASON
                || lotSizeReason.length() > MAX_REASON)) {
            throw new BadRequestException("Give a reason of " + MIN_REASON + " to " + MAX_REASON
                    + " characters to change the lot size.");
        }

        ChangeList changes = new ChangeList()
                .add("cooperativeName", setting.getCooperativeName(), cooperativeName)
                .add("smsEnabled", setting.isSmsEnabled(), request.smsEnabled())
                .add("cooperativeEmail", setting.getCooperativeEmail(), cooperativeEmail)
                .add("cooperativePhone", setting.getCooperativePhone(), cooperativePhone)
                .add("location", setting.getLocation(), location)
                .add("minOrderKg", setting.getMinOrderKg(), request.minOrderKg())
                .add("committeeEmails", setting.getCommitteeEmailList(), committeeEmails);

        setting.setSmsEnabled(request.smsEnabled());
        setting.setCooperativeEmail(cooperativeEmail);
        setting.setLocation(location);
        setting.setMinOrderKg(request.minOrderKg());
        setting.setCommitteeEmailList(committeeEmails);
        setting.setCooperativeName(cooperativeName);
        setting.setCooperativePhone(cooperativePhone);
        if (lotSizeChanged) {
            setting.setLotSizeKg(lotSizeKg);
        }

        if (!changes.isEmpty()) {
            auditLogRepository.save(AuditLog.of(AuditAction.SETTINGS_UPDATED, null, manager, changes.toString()));
        }
        if (lotSizeChanged) {
            auditLogRepository.save(AuditLog.of(AuditAction.LOT_SIZE_CHANGED, null, manager,
                    "Lot size: " + kg(oldLotSizeKg) + " kg -> " + kg(lotSizeKg) + " kg, for new lots only. Reason: "
                            + lotSizeReason));
        }
        return SettingsResponse.from(setting);
    }

    private Setting setting() {
        return settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."));
    }

    private static String kg(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
