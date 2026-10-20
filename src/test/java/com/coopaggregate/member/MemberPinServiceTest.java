package com.coopaggregate.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;

class MemberPinServiceTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private AuditLogRepository auditLogRepository;
    private Member member;
    private Manager manager;
    private MemberPinService service;

    @BeforeEach
    void setUp() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        member = new Member();
        ReflectionTestUtils.setField(member, "id", 5L);
        when(memberRepository.findForUpdateById(5L)).thenReturn(Optional.of(member));
        when(memberRepository.findForUpdateById(99L)).thenReturn(Optional.empty());
        manager = new Manager();
        service = new MemberPinService(memberRepository, encoder, auditLogRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2580", "1357", "0918", "1243", "9081"})
    void acceptablePinsAreAccepted(String pin) {
        PinPolicy.check(pin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000", "1111", "9999", "1234", "0123", "6789", "4321", "9876", "3210"})
    void trivialPinsAreRejected(String pin) {
        BadRequestException e = assertThrows(BadRequestException.class, () -> PinPolicy.check(pin));

        assertEquals("This PIN is too easy to guess. Do not use 4 equal digits or digits in a row like 1234 or 4321.",
                e.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"123", "12345", "12a4", " 2580", "2580 ", "٢٥٨٠"})
    void pinMustBeExactlyFourDigits(String pin) {
        BadRequestException e = assertThrows(BadRequestException.class, () -> PinPolicy.check(pin));

        assertEquals("The PIN must be exactly 4 digits.", e.getMessage());
    }

    @Test
    void firstPinIsStoredAsAHashAndLoggedAsSet() {
        service.setPin(5L, "2580", manager);

        assertTrue(member.hasPin());
        assertNotEquals("2580", member.getPinHash());
        assertTrue(encoder.matches("2580", member.getPinHash()));
        ArgumentCaptor<AuditLog> log = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(log.capture());
        assertEquals(AuditAction.PIN_SET, log.getValue().getAction());
        assertEquals(member, log.getValue().getMember());
        assertEquals(null, log.getValue().getDetails());
    }

    @Test
    void resetClearsTheLockAndIsLoggedAsReset() {
        member.changePin(encoder.encode("2580"), Instant.now());
        member.recordPinFailure(3, Instant.now());
        member.recordPinFailure(3, Instant.now());
        member.recordPinFailure(3, Instant.now());
        assertTrue(member.isPinLocked());

        service.setPin(5L, "1357", manager);

        assertFalse(member.isPinLocked());
        assertEquals(0, member.getPinFailedAttempts());
        assertTrue(encoder.matches("1357", member.getPinHash()));
        ArgumentCaptor<AuditLog> log = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(log.capture());
        assertEquals(AuditAction.PIN_RESET, log.getValue().getAction());
    }

    @Test
    void invalidPinChangesNothing() {
        assertThrows(BadRequestException.class, () -> service.setPin(5L, "1111", manager));

        assertFalse(member.hasPin());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void unknownMemberIsNotFound() {
        assertThrows(EntityNotFoundException.class, () -> service.setPin(99L, "2580", manager));
    }

    @Test
    void responseShowsPinStateButNeverThePin() {
        service.setPin(5L, "2580", manager);

        MemberResponse response = MemberResponse.from(member);

        assertTrue(response.hasPin());
        assertFalse(response.pinLocked());
        assertFalse(response.toString().contains(member.getPinHash()));
        assertFalse(new PinRequest("2580").toString().contains("2580"));
    }
}
