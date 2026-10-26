package com.coopaggregate.setting;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.audit.ChangeList;
import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.manager.Manager;

@Service
public class SettingService {

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

        if (!changes.isEmpty()) {
            auditLogRepository.save(AuditLog.of(AuditAction.SETTINGS_UPDATED, null, manager, changes.toString()));
        }
        return SettingsResponse.from(setting);
    }

    private Setting setting() {
        return settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
