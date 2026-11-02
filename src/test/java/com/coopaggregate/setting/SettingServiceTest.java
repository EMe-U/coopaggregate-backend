package com.coopaggregate.setting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.manager.Manager;

class SettingServiceTest {

    private AuditLogRepository auditLogRepository;
    private Setting setting;
    private Manager manager;
    private SettingService service;

    @BeforeEach
    void setUp() {
        SettingRepository settingRepository = mock(SettingRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        setting = new Setting();
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));
        manager = new Manager();
        service = new SettingService(settingRepository, auditLogRepository);
    }

    @Test
    void defaultsAreSmsOnAndMinimumOrderOf100Kg() {
        SettingsResponse settings = service.get();

        assertEquals(true, settings.smsEnabled());
        assertEquals(0, settings.minOrderKg().compareTo(new BigDecimal("100")));
        assertEquals(List.of(), settings.committeeEmails());
        assertEquals(5L, settings.deductionPerKg());
    }

    @Test
    void cooperativeNameIsChangedAndRecordedButNotEmptied() {
        SettingsResponse renamed = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(), " Koperative Abahinzi ", null), manager);
        SettingsResponse kept = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(), " ", null), manager);

        assertEquals("Koperative Abahinzi", renamed.cooperativeName());
        assertEquals("Koperative Abahinzi", kept.cooperativeName());
        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(entry.capture());
        assertEquals("cooperativeName: Koperative Intagamburuzwa -> Koperative Abahinzi",
                entry.getValue().getDetails());
    }

    @Test
    void cooperativePhoneIsStoredAsInternationalNumberRecordedAndCanBeCleared() {
        SettingsResponse saved = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(), null, " 0788123456 "), manager);
        SettingsResponse cleared = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(), null, ""), manager);

        assertEquals("+250788123456", saved.cooperativePhone());
        assertEquals(null, cleared.cooperativePhone());
        ArgumentCaptor<AuditLog> entries = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertEquals("cooperativePhone: (empty) -> +250788123456", entries.getAllValues().get(0).getDetails());
        assertEquals("cooperativePhone: +250788123456 -> (empty)", entries.getAllValues().get(1).getDetails());
    }

    @Test
    void savedSettingsAreReturned() {
        SettingsResponse saved = service.update(new SettingsRequest(false, "info@intagamburuzwa.rw",
                "Busogo, Musanze", new BigDecimal("250"), List.of("chair@example.com", "treasurer@example.com"), null, null),
                manager);

        assertEquals(false, saved.smsEnabled());
        assertEquals("info@intagamburuzwa.rw", saved.cooperativeEmail());
        assertEquals("Busogo, Musanze", saved.location());
        assertEquals(new BigDecimal("250"), saved.minOrderKg());
        assertEquals(List.of("chair@example.com", "treasurer@example.com"), saved.committeeEmails());
    }

    @Test
    void changeHistoryListsOnlyTheFieldsThatChanged() {
        service.update(new SettingsRequest(false, null, "Busogo", new BigDecimal("100.00"),
                List.of("chair@example.com"), null, null), manager);

        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(entry.capture());
        assertEquals(AuditAction.SETTINGS_UPDATED, entry.getValue().getAction());
        assertEquals(manager, entry.getValue().getManager());
        assertEquals("smsEnabled: true -> false; location: (empty) -> Busogo; "
                + "committeeEmails: (empty) -> chair@example.com", entry.getValue().getDetails());
    }

    @Test
    void savingWithoutChangesWritesNoHistory() {
        service.update(new SettingsRequest(true, " ", "", new BigDecimal("100"), List.of(), null, null), manager);

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void committeeEmailsAreTrimmedLowercasedAndDeduplicated() {
        SettingsResponse saved = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(" Chair@Example.com ", "chair@example.com", "TREASURER@example.com"), null, null), manager);

        assertEquals(List.of("chair@example.com", "treasurer@example.com"), saved.committeeEmails());
    }

    @Test
    void emptyListRemovesAllCommitteeEmails() {
        setting.setCommitteeEmailList(List.of("chair@example.com"));

        SettingsResponse saved = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(), null, null), manager);

        assertEquals(List.of(), saved.committeeEmails());
        assertNull(saved.cooperativeEmail());
    }
}
