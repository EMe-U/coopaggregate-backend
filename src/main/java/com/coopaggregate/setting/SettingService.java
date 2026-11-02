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
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.manager.Manager;

@Service
public class SettingService {

    private final SettingRepository settingRepository;
    private final AuditLogRepository auditLogRepository;
    private final LotRepository lotRepository;

    public SettingService(SettingRepository settingRepository, AuditLogRepository auditLogRepository,
                          LotRepository lotRepository) {
        this.settingRepository = settingRepository;
        this.auditLogRepository = auditLogRepository;
        this.lotRepository = lotRepository;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get() {
        return SettingsResponse.from(setting(), lotRepository.existsByCapacityKgIsNotNull());
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

        // Every lot of the cooperative has the same size, so it is fixed once the first fixed-size lot exists.
        boolean lotSizeLocked = lotRepository.existsByCapacityKgIsNotNull();
        BigDecimal lotSizeKg = request.lotSizeKg() == null ? setting.getLotSizeKg() : request.lotSizeKg();
        if (lotSizeKg.compareTo(setting.getLotSizeKg()) != 0 && lotSizeLocked) {
            throw new IllegalStateException("The lot size is " + setting.getLotSizeKg().stripTrailingZeros()
                    .toPlainString() + " kg and can no longer change: lots of that size already exist.");
        }

        ChangeList changes = new ChangeList()
                .add("cooperativeName", setting.getCooperativeName(), cooperativeName)
                .add("smsEnabled", setting.isSmsEnabled(), request.smsEnabled())
                .add("cooperativeEmail", setting.getCooperativeEmail(), cooperativeEmail)
                .add("cooperativePhone", setting.getCooperativePhone(), cooperativePhone)
                .add("location", setting.getLocation(), location)
                .add("minOrderKg", setting.getMinOrderKg(), request.minOrderKg())
                .add("lotSizeKg", setting.getLotSizeKg(), lotSizeKg)
                .add("committeeEmails", setting.getCommitteeEmailList(), committeeEmails);

        setting.setSmsEnabled(request.smsEnabled());
        setting.setCooperativeEmail(cooperativeEmail);
        setting.setLocation(location);
        setting.setMinOrderKg(request.minOrderKg());
        setting.setCommitteeEmailList(committeeEmails);
        setting.setCooperativeName(cooperativeName);
        setting.setCooperativePhone(cooperativePhone);
        if (lotSizeKg.compareTo(setting.getLotSizeKg()) != 0) {
            setting.setLotSizeKg(lotSizeKg);
        }

        if (!changes.isEmpty()) {
            auditLogRepository.save(AuditLog.of(AuditAction.SETTINGS_UPDATED, null, manager, changes.toString()));
        }
        return SettingsResponse.from(setting, lotSizeLocked);
    }

    private Setting setting() {
        return settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
