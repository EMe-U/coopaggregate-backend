package com.coopaggregate.setting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.coopaggregate.error.BadRequestException;
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

    // --- Lot size: can change at any time, with a reason; only lots opened afterwards get the new size ---

    @Test
    void theLotSizeIs100KgByDefault() {
        assertEquals(0, service.get().lotSizeKg().compareTo(new BigDecimal("100")));
    }

    @Test
    void theLotSizeChangesWithAReasonAndTheChangeIsRecorded() {
        SettingsResponse saved = service.update(lotSize("150", "  Bigger trucks from the new buyer  "), manager);

        assertEquals(0, saved.lotSizeKg().compareTo(new BigDecimal("150")));
        assertEquals(0, setting.getLotSizeKg().compareTo(new BigDecimal("150")));
        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(entry.capture());
        assertEquals(AuditAction.LOT_SIZE_CHANGED, entry.getValue().getAction());
        assertEquals("Lot size: 100 kg -> 150 kg, for new lots only. Reason: Bigger trucks from the new buyer",
                entry.getValue().getDetails());
        assertEquals(manager, entry.getValue().getManager());
    }

    @Test
    void theLotSizeCanChangeAgainLater() {
        service.update(lotSize("150", "Bigger trucks from the new buyer"), manager);
        service.update(lotSize("80.5", "Back to small sacks for the rainy season"), manager);

        ArgumentCaptor<AuditLog> entries = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertEquals("Lot size: 150 kg -> 80.5 kg, for new lots only. Reason: Back to small sacks for the rainy season",
                entries.getAllValues().get(1).getDetails());
        assertEquals(0, setting.getLotSizeKg().compareTo(new BigDecimal("80.5")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"   ", "Too short", "         x         "})
    void aNewLotSizeNeedsAReasonOfAtLeast10Characters(String reason) {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.update(lotSize("150", reason), manager));

        assertEquals("Give a reason of 10 to 500 characters to change the lot size.", e.getMessage());
        assertEquals(0, setting.getLotSizeKg().compareTo(new BigDecimal("100")));
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void theReasonCanHaveAtMost500Characters() {
        assertThrows(BadRequestException.class, () -> service.update(lotSize("150", "x".repeat(501)), manager));

        service.update(lotSize("150", "x".repeat(500)), manager);
        assertEquals(0, setting.getLotSizeKg().compareTo(new BigDecimal("150")));
    }

    @Test
    void theSameLotSizeOrNoneAtAllNeedsNoReasonAndWritesNoLotSizeEntry() {
        service.update(new SettingsRequest(true, null, "Busogo", new BigDecimal("100"), List.of(), null, null,
                new BigDecimal("100.00"), null), manager);
        SettingsResponse saved = service.update(new SettingsRequest(true, null, "Musanze", new BigDecimal("100"),
                List.of(), null, null, null, "A reason that is not used"), manager);

        assertEquals("Musanze", saved.location());
        assertEquals(0, saved.lotSizeKg().compareTo(new BigDecimal("100")));
        ArgumentCaptor<AuditLog> entries = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        entries.getAllValues().forEach(entry -> assertEquals(AuditAction.SETTINGS_UPDATED, entry.getAction()));
    }

    @Test
    void otherSettingsAndTheLotSizeAreRecordedSeparately() {
        service.update(new SettingsRequest(true, null, "Musanze", new BigDecimal("100"), List.of(), null, null,
                new BigDecimal("120"), "Matches the new store shelves"), manager);

        ArgumentCaptor<AuditLog> entries = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertEquals("location: (empty) -> Musanze", entries.getAllValues().get(0).getDetails());
        assertEquals(AuditAction.LOT_SIZE_CHANGED, entries.getAllValues().get(1).getAction());
    }

    private static SettingsRequest lotSize(String kg, String reason) {
        return new SettingsRequest(true, null, null, new BigDecimal("100"), List.of(), null, null,
                new BigDecimal(kg), reason);
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
                List.of(), " Koperative Abahinzi ", null, null, null), manager);
        SettingsResponse kept = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(), " ", null, null, null), manager);

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
                List.of(), null, " 0788123456 ", null, null), manager);
        SettingsResponse cleared = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(), null, "", null, null), manager);

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
                "Busogo, Musanze", new BigDecimal("250"), List.of("chair@example.com", "treasurer@example.com"), null, null, null, null),
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
                List.of("chair@example.com"), null, null, null, null), manager);

        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(entry.capture());
        assertEquals(AuditAction.SETTINGS_UPDATED, entry.getValue().getAction());
        assertEquals(manager, entry.getValue().getManager());
        assertEquals("smsEnabled: true -> false; location: (empty) -> Busogo; "
                + "committeeEmails: (empty) -> chair@example.com", entry.getValue().getDetails());
    }

    @Test
    void savingWithoutChangesWritesNoHistory() {
        service.update(new SettingsRequest(true, " ", "", new BigDecimal("100"), List.of(), null, null, null, null), manager);

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void committeeEmailsAreTrimmedLowercasedAndDeduplicated() {
        SettingsResponse saved = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(" Chair@Example.com ", "chair@example.com", "TREASURER@example.com"), null, null, null, null), manager);

        assertEquals(List.of("chair@example.com", "treasurer@example.com"), saved.committeeEmails());
    }

    @Test
    void emptyListRemovesAllCommitteeEmails() {
        setting.setCommitteeEmailList(List.of("chair@example.com"));

        SettingsResponse saved = service.update(new SettingsRequest(true, null, null, new BigDecimal("100"),
                List.of(), null, null, null, null), manager);

        assertEquals(List.of(), saved.committeeEmails());
        assertNull(saved.cooperativeEmail());
    }
}
