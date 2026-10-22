package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

@ExtendWith(OutputCaptureExtension.class)
class IdentityVerificationServiceTest {

    private final Map<String, IdentityVerification> stored = new HashMap<>();
    private IdentityVerificationRepository verificationRepository;
    private MemberRepository memberRepository;
    private AuditLogRepository auditLogRepository;
    private IdentityVerifier verifier;
    private Manager manager;
    private IdentityVerificationService service;

    @BeforeEach
    void setUp() {
        verificationRepository = mock(IdentityVerificationRepository.class);
        when(verificationRepository.save(any(IdentityVerification.class))).thenAnswer(invocation -> {
            IdentityVerification verification = invocation.getArgument(0);
            ReflectionTestUtils.setField(verification, "id", 9L);
            stored.put(verification.getStateHash(), verification);
            return verification;
        });
        when(verificationRepository.findForUpdateByStateHash(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.<String>getArgument(0))));
        when(verificationRepository.findForUpdateById(9L))
                .thenAnswer(invocation -> stored.values().stream().findFirst());
        memberRepository = mock(MemberRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        verifier = new MockIdentityVerifier(true);
        manager = manager(1L);
        service = new IdentityVerificationService(verificationRepository, memberRepository, auditLogRepository,
                verifier);
    }

    @Test
    void startGivesARandomStateAndStoresOnlyItsHash() {
        StartVerificationResponse first = service.start(manager);
        StartVerificationResponse second = service.start(manager);

        assertEquals("MOCK", first.provider());
        assertNull(first.authorizationUrl());
        assertTrue(first.state().length() >= 43);
        assertNotEquals(first.state(), second.state());
        IdentityVerification saved = stored.get(IdentityVerificationService.sha256(first.state()));
        assertNotNull(saved);
        assertNotEquals(first.state(), saved.getStateHash());
        assertTrue(Duration.between(Instant.now(), first.expiresAt()).compareTo(Duration.ofMinutes(10)) <= 0);
    }

    @Test
    void mockFlowVerifiesThePersonAndWritesTheHistoryWithoutPersonalData() {
        String state = service.start(manager).state();

        VerifyResultResponse result = service.complete("any-code", state, manager);

        assertTrue(result.verified());
        assertEquals(VerificationMethod.MOCK, result.method());
        assertEquals(9L, result.verificationId());
        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        assertTrue(verification.isVerified());
        assertTrue(verification.getIdentityToken().startsWith("mock-"));
        AuditLog audit = lastAudit();
        assertEquals(AuditAction.IDENTITY_VERIFIED, audit.getAction());
        assertEquals("Provider MOCK, method MOCK", audit.getDetails());
        assertNull(audit.getMember());
    }

    @Test
    void stateCanOnlyBeUsedOnce() {
        String state = service.start(manager).state();
        service.complete("any-code", state, manager);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.complete("any-code", state, manager));

        assertEquals("This verification was already used. Start the verification again.", e.getMessage());
    }

    @Test
    void expiredStateIsRefused() {
        String state = service.start(manager).state();
        stored.get(IdentityVerificationService.sha256(state)).setExpiresAt(Instant.now().minusSeconds(1));

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.complete("any-code", state, manager));

        assertEquals("This verification has expired. Start the verification again.", e.getMessage());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void unknownStateIsRefused() {
        assertThrows(BadRequestException.class, () -> service.complete("any-code", "made-up-state", manager));
    }

    @Test
    void anotherManagersStateIsRefused() {
        String state = service.start(manager).state();

        assertThrows(BadRequestException.class, () -> service.complete("any-code", state, manager(2L)));
    }

    @Test
    void personWhoIsAlreadyAMemberIsRefusedWithoutDetails() {
        String state = service.start(manager).state();
        when(memberRepository.existsByIdentityToken(anyString())).thenReturn(true);

        DuplicateIdentityException e = assertThrows(DuplicateIdentityException.class,
                () -> service.complete("any-code", state, manager));

        assertEquals("This person is already a member.", e.getMessage());
        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        assertFalse(verification.isVerified());
        assertNotNull(verification.getUsedAt());
        AuditLog audit = lastAudit();
        assertEquals(AuditAction.IDENTITY_DUPLICATE, audit.getAction());
        assertEquals("Provider MOCK, method MOCK: the person is already a member", audit.getDetails());
    }

    @Test
    void providerFailureUsesTheStateAndIsRecorded(CapturedOutput output) {
        verifier = mock(IdentityVerifier.class);
        when(verifier.name()).thenReturn("MOSIP");
        when(verifier.complete(anyString(), anyString()))
                .thenThrow(IdentityProviderException.notConfigured("MOSIP verification is not configured yet."));
        service = new IdentityVerificationService(verificationRepository, memberRepository, auditLogRepository,
                verifier);
        String state = service.start(manager).state();

        assertThrows(IdentityProviderException.class, () -> service.complete("secret-code-123", state, manager));

        assertNotNull(stored.get(IdentityVerificationService.sha256(state)).getUsedAt());
        assertEquals(AuditAction.IDENTITY_VERIFICATION_FAILED, lastAudit().getAction());
        assertEquals("Provider MOSIP: not configured", lastAudit().getDetails());
        assertFalse(output.getAll().contains("secret-code-123"));
        assertFalse(output.getAll().contains(state));
    }

    @Test
    void stateIsUsedUpAndHistoryKeptEvenWhenTheVerificationFails() throws Exception {
        Transactional transactional = IdentityVerificationService.class
                .getMethod("complete", String.class, String.class, Manager.class)
                .getAnnotation(Transactional.class);

        assertEquals(List.of(IdentityProviderException.class, DuplicateIdentityException.class),
                List.of(transactional.noRollbackFor()));
    }

    @Test
    void switchedOffMockRefusesToStartAndSavesNothing() {
        service = new IdentityVerificationService(verificationRepository, memberRepository, auditLogRepository,
                new MockIdentityVerifier(false));

        IdentityProviderException e = assertThrows(IdentityProviderException.class, () -> service.start(manager));

        assertTrue(e.isNotConfigured());
        assertEquals("Test verification is switched off.", e.getMessage());
        assertTrue(stored.isEmpty());
    }

    @Test
    void switchedOffMockRefusesTheCallbackAndNobodyIsVerified() {
        String state = service.start(manager).state();
        service = new IdentityVerificationService(verificationRepository, memberRepository, auditLogRepository,
                new MockIdentityVerifier(false));

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> service.complete("any-code", state, manager));

        assertEquals("Test verification is switched off.", e.getMessage());
        assertFalse(stored.get(IdentityVerificationService.sha256(state)).isVerified());
        assertEquals(AuditAction.IDENTITY_VERIFICATION_FAILED, lastAudit().getAction());
    }

    @Test
    void startupLogTellsHowToSwitchTestVerificationOnButTheMessageDoesNot(CapturedOutput output) {
        new MockIdentityVerifier(false);

        assertTrue(output.getAll().contains("Set MOCK_VERIFICATION_ALLOWED=true"));
        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> new MockIdentityVerifier(false).complete("code", "nonce"));
        assertFalse(e.getMessage().contains("MOCK_VERIFICATION_ALLOWED"));
    }

    @Test
    void mockGivesTheSamePersonForTheSameCode() {
        MockIdentityVerifier mock = new MockIdentityVerifier(true);

        assertEquals(mock.complete("code-1", "n").subject(), mock.complete("code-1", "n").subject());
        assertNotEquals(mock.complete("code-1", "n").subject(), mock.complete("code-2", "n").subject());
        assertEquals("VerifiedIdentity[subject=****, method=MOCK]", mock.complete("code-1", "n").toString());
    }

    // --- Registering the member with the verification ---

    @Test
    void attachMarksTheNewMemberVerified() {
        IdentityVerification verification = completedVerification();
        Member member = new Member();

        service.attach(9L, member);

        assertTrue(member.isIdentityVerified());
        assertEquals(VerificationMethod.MOCK, member.getVerificationMethod());
        assertEquals(verification.getVerifiedAt(), member.getVerifiedAt());
        assertEquals(verification.getIdentityToken(), member.getIdentityToken());
        assertEquals(member, verification.getMember());
    }

    @Test
    void verificationRegistersOnlyOneMember() {
        completedVerification();
        service.attach(9L, new Member());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.attach(9L, new Member()));

        assertEquals("Verification 9 was already used to register a member.", e.getMessage());
    }

    @Test
    void verificationThatDidNotVerifyAnyoneCannotBeAttached() {
        service.start(manager);

        assertThrows(BadRequestException.class, () -> service.attach(9L, new Member()));
    }

    @Test
    void oldVerificationCannotBeAttached() {
        IdentityVerification verification = completedVerification();
        ReflectionTestUtils.setField(verification, "verifiedAt", Instant.now().minus(Duration.ofMinutes(31)));

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.attach(9L, new Member()));

        assertEquals("Verification 9 is more than 30 minutes old. Verify the person again.", e.getMessage());
    }

    @Test
    void personWhoBecameAMemberInTheMeantimeIsRefused() {
        completedVerification();
        when(memberRepository.existsByIdentityToken(anyString())).thenReturn(true);
        Member member = new Member();

        assertThrows(DuplicateIdentityException.class, () -> service.attach(9L, member));
        assertFalse(member.isIdentityVerified());
    }

    private IdentityVerification completedVerification() {
        String state = service.start(manager).state();
        service.complete("any-code", state, manager);
        return stored.get(IdentityVerificationService.sha256(state));
    }

    private AuditLog lastAudit() {
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, org.mockito.Mockito.atLeastOnce()).save(audit.capture());
        return audit.getValue();
    }

    private static Manager manager(Long id) {
        Manager manager = new Manager();
        ReflectionTestUtils.setField(manager, "id", id);
        return manager;
    }
}
