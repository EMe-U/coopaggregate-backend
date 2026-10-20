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
    void savedSettingsAreReturned() {
        SettingsResponse saved = service.update(new SettingsRequest(false, "info@intagamburuzwa.rw",
                "Busogo, Musanze", new BigDecimal("250"), List.of("chair@example.com", "treasurer@example.com")),
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
                List.of("chair@example.com")), manager);

        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(entry.capture());
        assertEquals(AuditAction.SETTINGS_UPDATED, entry.getValue().getAction());
        assertEquals(manager, entry.getValue().getManager());
        assertEquals("smsEnabled: true -> false; location: (empty) -> Busogo; "
                + "committeeEmails: (empty) -> chair@example.com", entry.getValue().getDetails());
    }

    @Test
    void savingWithoutChangesWritesNoHistory() {
        service.update(new SettingsRequest(true, " ", "", new BigDecimal("100"), List.of()), manager);

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void committeeEmailsAreTrimmedLowercasedAndDeduplicated() {
        SettingsResponse saved = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(" Chair@Example.com ", "chair@example.com", "TREASURER@example.com")), manager);

        assertEquals(List.of("chair@example.com", "treasurer@example.com"), saved.committeeEmails());
    }

    @Test
    void emptyListRemovesAllCommitteeEmails() {
        setting.setCommitteeEmailList(List.of("chair@example.com"));

        SettingsResponse saved = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of()), manager);

        assertEquals(List.of(), saved.committeeEmails());
        assertNull(saved.cooperativeEmail());
    }
}
