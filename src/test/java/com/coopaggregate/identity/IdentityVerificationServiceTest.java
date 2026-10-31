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
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.MemberRepository;

@ExtendWith(OutputCaptureExtension.class)
class IdentityVerificationServiceTest {

    private static final MosipClaims CLAIMS = new MosipClaims("pairwise-sub", "Uwimana Claudine", "1990/05/17",
            "+250788123456", "claudine@example.com");

    private final Map<String, IdentityVerification> stored = new HashMap<>();
    private IdentityVerificationRepository verificationRepository;
    private MemberRepository memberRepository;
    private AuditLogRepository auditLogRepository;
    private MosipClient client;
    private ObjectProvider<MosipClient> clientProvider;
    private Manager manager;
    private IdentityVerificationService service;

    @SuppressWarnings("unchecked")
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
        memberRepository = mock(MemberRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        client = mock(MosipClient.class);
        when(client.authorizationUrl(anyString(), anyString(), anyString()))
                .thenReturn("http://localhost:8080/oauth2/authorize?x=y");
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(CLAIMS);
        clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getIfAvailable()).thenReturn(client);
        manager = manager(1L);
        service = new IdentityVerificationService(verificationRepository, memberRepository, auditLogRepository,
                clientProvider);
    }

    // --- Start

    @Test
    void startGivesARandomStateAndStoresOnlyItsHash() {
        StartVerificationResponse first = service.start(manager);
        StartVerificationResponse second = service.start(manager);

        assertEquals("MOSIP", first.provider());
        assertEquals("http://localhost:8080/oauth2/authorize?x=y", first.authorizationUrl());
        assertTrue(first.state().length() >= 43);
        assertNotEquals(first.state(), second.state());
        IdentityVerification saved = stored.get(IdentityVerificationService.sha256(first.state()));
        assertNotNull(saved);
        assertNotEquals(first.state(), saved.getStateHash());
        assertTrue(Duration.between(Instant.now(), first.expiresAt()).compareTo(Duration.ofMinutes(10)) <= 0);
    }

    @Test
    void startSendsTheProviderTheChallengeOfTheVerifierItKeeps() {
        String state = service.start(manager).state();

        IdentityVerification saved = stored.get(IdentityVerificationService.sha256(state));
        ArgumentCaptor<String> challenge = ArgumentCaptor.forClass(String.class);
        verify(client).authorizationUrl(eq(state), eq(saved.getNonce()), challenge.capture());
        assertEquals(43, saved.getCodeVerifier().length());
        assertEquals(Pkce.challenge(saved.getCodeVerifier()), challenge.getValue());
        assertNotEquals(saved.getCodeVerifier(), challenge.getValue());
    }

    @Test
    void startWhenVerificationIsNotAvailableSavesNothing() {
        when(clientProvider.getIfAvailable()).thenReturn(null);

        IdentityProviderException e = assertThrows(IdentityProviderException.class, () -> service.start(manager));

        assertTrue(e.isNotConfigured());
        assertEquals("Identity verification is not available.", e.getMessage());
        assertTrue(stored.isEmpty());
    }

    // --- Complete

    @Test
    void completeVerifiesThePersonWithTheVerifierAndNonceOfThatStart() {
        String state = service.start(manager).state();
        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        String codeVerifier = verification.getCodeVerifier();
        String nonce = verification.getNonce();

        VerifyResultResponse result = service.complete("the-code", state, manager);

        verify(client).fetchClaims("the-code", codeVerifier, nonce);
        assertTrue(result.verified());
        assertEquals(VerificationMethod.PASSWORD, result.method());
        assertEquals(9L, result.verificationId());
        assertTrue(Duration.between(Instant.now(), result.useBefore()).compareTo(Duration.ofMinutes(30)) <= 0);
        assertTrue(verification.isVerified());
        assertEquals("pairwise-sub", verification.getIdentityToken());
        assertEquals(VerificationMethod.PASSWORD, verification.getVerificationMethod());
        assertNull(verification.getCodeVerifier(), "the PKCE verifier is gone once the state is used");
    }

    @Test
    void onlySaltedHashesOfTheDetailsAreKept() {
        String state = service.start(manager).state();

        service.complete("the-code", state, manager);

        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        String salt = verification.getSalt();
        assertEquals(ClaimFingerprints.hash(salt, ClaimFingerprints.NAME, "claudine uwimana"),
                verification.getNameHash());
        assertEquals(ClaimFingerprints.hash(salt, ClaimFingerprints.BIRTH, "1990-05-17"), verification.getBirthHash());
        assertEquals(ClaimFingerprints.hash(salt, ClaimFingerprints.PHONE, "+250788123456"),
                verification.getPhoneHash());
        String everything = String.join(" ", salt, verification.getNameHash(), verification.getBirthHash(),
                verification.getPhoneHash(), verification.getIdentityToken(), verification.getNonce());
        for (String detail : new String[] {"Uwimana", "Claudine", "1990", "788123456", "claudine@example.com"}) {
            assertFalse(everything.toLowerCase().contains(detail.toLowerCase()), detail);
        }
    }

    @Test
    void theHistoryHoldsNoPersonalData() {
        String state = service.start(manager).state();

        service.complete("the-code", state, manager);

        AuditLog audit = lastAudit();
        assertEquals(AuditAction.IDENTITY_VERIFIED, audit.getAction());
        assertEquals("Provider MOSIP, method PASSWORD", audit.getDetails());
        assertNull(audit.getMember());
    }

    @Test
    void stateCanOnlyBeUsedOnce() {
        String state = service.start(manager).state();
        service.complete("the-code", state, manager);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.complete("the-code", state, manager));

        assertEquals("This verification was already used. Start the verification again.", e.getMessage());
        verify(client).fetchClaims(anyString(), anyString(), anyString());
    }

    @Test
    void expiredStateIsRefusedAndTheProviderIsNotAsked() {
        String state = service.start(manager).state();
        stored.get(IdentityVerificationService.sha256(state)).setExpiresAt(Instant.now().minusSeconds(1));

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.complete("the-code", state, manager));

        assertEquals("This verification has expired. Start the verification again.", e.getMessage());
        verify(client, never()).fetchClaims(anyString(), anyString(), anyString());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void unknownStateIsRefused() {
        assertThrows(BadRequestException.class, () -> service.complete("the-code", "made-up-state", manager));
        verify(client, never()).fetchClaims(anyString(), anyString(), anyString());
    }

    @Test
    void anotherManagersStateIsRefused() {
        String state = service.start(manager).state();

        assertThrows(BadRequestException.class, () -> service.complete("the-code", state, manager(2L)));
        assertNull(stored.get(IdentityVerificationService.sha256(state)).getUsedAt());
    }

    @Test
    void personWhoIsAlreadyAMemberIsRefusedWithoutDetails() {
        String state = service.start(manager).state();
        when(memberRepository.existsByIdentityToken("pairwise-sub")).thenReturn(true);

        DuplicateIdentityException e = assertThrows(DuplicateIdentityException.class,
                () -> service.complete("the-code", state, manager));

        assertEquals("This person is already a member.", e.getMessage());
        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        assertFalse(verification.isVerified());
        assertNotNull(verification.getUsedAt());
        assertNull(verification.getNameHash(), "nothing is kept for a person who cannot register");
        AuditLog audit = lastAudit();
        assertEquals(AuditAction.IDENTITY_DUPLICATE, audit.getAction());
        assertEquals("Provider MOSIP, method PASSWORD: the person is already a member", audit.getDetails());
    }

    @Test
    void providerFailureUsesTheStateAndIsRecorded(CapturedOutput output) {
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenThrow(
                IdentityProviderException.failed("The identity provider refused the token request."));
        String state = service.start(manager).state();

        assertThrows(IdentityProviderException.class, () -> service.complete("secret-code-123", state, manager));

        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        assertNotNull(verification.getUsedAt());
        assertFalse(verification.isVerified());
        assertEquals(AuditAction.IDENTITY_VERIFICATION_FAILED, lastAudit().getAction());
        assertEquals("Provider MOSIP: provider error", lastAudit().getDetails());
        assertFalse(output.getAll().contains("secret-code-123"));
        assertFalse(output.getAll().contains(state));
        assertFalse(output.getAll().contains(verification.getNonce()));
    }

    @Test
    void whenVerificationIsSwitchedOffTheCallbackIsRefusedAndRecorded() {
        String state = service.start(manager).state();
        when(clientProvider.getIfAvailable()).thenReturn(null);

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> service.complete("the-code", state, manager));

        assertTrue(e.isNotConfigured());
        assertEquals("Provider MOSIP: not configured", lastAudit().getDetails());
        assertFalse(stored.get(IdentityVerificationService.sha256(state)).isVerified());
    }

    @Test
    void aProviderThatLeavesOutADetailVerifiesNobody(CapturedOutput output) {
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", "Uwimana Claudine", null, "0711111111", null));
        String state = service.start(manager).state();

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> service.complete("the-code", state, manager));

        assertFalse(e.isNotConfigured());
        assertEquals("The identity provider did not give the name, birth date and phone number needed to check "
                + "the member.", e.getMessage());
        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        assertFalse(verification.isVerified());
        assertNull(verification.getSalt());
        assertEquals("Provider MOSIP: missing or unreadable details: birthDate, phone", lastAudit().getDetails());
        assertFalse(output.getAll().contains("Uwimana"));
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
    void nothingPersonalIsEverLogged(CapturedOutput output) {
        String state = service.start(manager).state();
        service.complete("secret-code-123", state, manager);

        String logs = output.getAll();
        for (String secret : new String[] {"secret-code-123", state, "Uwimana", "Claudine", "1990", "788123456",
                "claudine@example.com", "pairwise-sub"}) {
            assertFalse(logs.contains(secret), secret);
        }
    }

    // --- Cleaning up

    @Test
    void clearExpiredDropsOldVerifiersAndFingerprints() {
        when(verificationRepository.clearUnusedVerifiers(any(Instant.class))).thenReturn(2);
        when(verificationRepository.clearOldFingerprints(any(Instant.class))).thenReturn(1);

        assertEquals(3, service.clearExpired());

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(verificationRepository).clearOldFingerprints(cutoff.capture());
        assertTrue(Duration.between(cutoff.getValue(), Instant.now()).toMinutes() >= 29);
    }

    private AuditLog lastAudit() {
        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, org.mockito.Mockito.atLeastOnce()).save(entry.capture());
        return entry.getValue();
    }

    private static Manager manager(Long id) {
        Manager manager = new Manager();
        ReflectionTestUtils.setField(manager, "id", id);
        return manager;
    }
}
