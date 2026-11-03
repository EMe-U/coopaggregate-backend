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
import java.time.LocalDate;
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
import org.springframework.transaction.annotation.Propagation;
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
        when(verificationRepository.findForUpdateById(9L))
                .thenAnswer(invocation -> stored.values().stream().findFirst());
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

    // --- Checking the details of the new member

    @Test
    void detailsThatMatchChangeNothing() {
        Long id = verified();

        service.checkDetails(id, "Uwimana Claudine", LocalDate.of(1990, 5, 17), "0788123456");

        IdentityVerification verification = stored.values().iterator().next();
        assertEquals(0, verification.getAttempts());
        assertNotNull(verification.getSalt(), "the hashes stay until the member is saved");
        verify(auditLogRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void theNameIsMatchedWithoutCaseAccentsOrWordOrder() {
        Long id = verified();

        service.checkDetails(id, "  claudine   UWIMANA ", LocalDate.of(1990, 5, 17), "+250788123456");
        service.checkDetails(id, "Ûwimana Claudiné", LocalDate.of(1990, 5, 17), "250788123456");
    }

    @Test
    void aWrongNameIsRefusedAndNamesTheField() {
        Long id = verified();

        IdentityMismatchException e = assertThrows(IdentityMismatchException.class,
                () -> service.checkDetails(id, "Uwimana Claudette", LocalDate.of(1990, 5, 17), "0788123456"));

        assertEquals("The details do not match the person who verified: name. Correct them and try again "
                + "(attempt 1 of 3).", e.getMessage());
    }

    @Test
    void aWrongBirthDateIsRefused() {
        Long id = verified();

        IdentityMismatchException e = assertThrows(IdentityMismatchException.class,
                () -> service.checkDetails(id, "Uwimana Claudine", LocalDate.of(1990, 5, 18), "0788123456"));

        assertTrue(e.getMessage().contains("verified: birthDate."), e.getMessage());
    }

    @Test
    void aWrongPhoneIsRefusedAndBlocksLikeTheOthers() {
        Long id = verified();

        IdentityMismatchException e = assertThrows(IdentityMismatchException.class,
                () -> service.checkDetails(id, "Uwimana Claudine", LocalDate.of(1990, 5, 17), "0788999999"));

        assertTrue(e.getMessage().contains("verified: phone."), e.getMessage());
    }

    @Test
    void everyFieldThatDiffersIsNamed() {
        Long id = verified();

        IdentityMismatchException e = assertThrows(IdentityMismatchException.class,
                () -> service.checkDetails(id, "Someone Else", LocalDate.of(2000, 1, 1), "0788999999"));

        assertTrue(e.getMessage().contains("verified: name, birthDate, phone."), e.getMessage());
    }

    @Test
    void aMismatchIsRecordedWithFieldNamesOnly() {
        Long id = verified();

        assertThrows(IdentityMismatchException.class,
                () -> service.checkDetails(id, "Someone Else", LocalDate.of(2000, 1, 1), "0788999999"));

        AuditLog audit = lastAudit();
        assertEquals(AuditAction.IDENTITY_MISMATCH, audit.getAction());
        assertEquals("Verification 9: details do not match: name, birthDate, phone (attempt 1 of 3)",
                audit.getDetails());
        assertNull(audit.getMember());
        for (String value : new String[] {"Someone", "Else", "2000", "788999999", "Claudine", "Uwimana", "1990"}) {
            assertFalse(audit.getDetails().contains(value), value);
        }
    }

    @Test
    void aMismatchIsNeverLogged(CapturedOutput output) {
        Long id = verified();

        assertThrows(IdentityMismatchException.class,
                () -> service.checkDetails(id, "Someone Else", LocalDate.of(2000, 1, 1), "0788999999"));

        String logs = output.getAll();
        for (String value : new String[] {"Someone", "Else", "2000", "788999999", "Claudine", "Uwimana", "1990",
                "788123456"}) {
            assertFalse(logs.contains(value), value);
        }
        assertTrue(logs.contains("name, birthDate, phone"), "the field names are logged");
    }

    @Test
    void theVerificationCanBeTriedAgainAfterAMismatchUpToThreeTimes() {
        Long id = verified();

        for (int attempt = 1; attempt <= 2; attempt++) {
            int shown = attempt;
            IdentityMismatchException e = assertThrows(IdentityMismatchException.class,
                    () -> service.checkDetails(id, "Wrong Name", LocalDate.of(1990, 5, 17), "0788123456"));
            assertTrue(e.getMessage().endsWith("(attempt " + shown + " of 3)."), e.getMessage());
        }

        service.checkDetails(id, "Uwimana Claudine", LocalDate.of(1990, 5, 17), "0788123456");
        assertEquals(2, stored.values().iterator().next().getAttempts());
    }

    @Test
    void theThirdMismatchUsesTheVerificationUp() {
        Long id = verified();
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThrows(IdentityMismatchException.class,
                    () -> service.checkDetails(id, "Wrong Name", LocalDate.of(1990, 5, 17), "0788123456"));
        }

        IdentityMismatchException third = assertThrows(IdentityMismatchException.class,
                () -> service.checkDetails(id, "Wrong Name", LocalDate.of(1990, 5, 17), "0788123456"));

        assertEquals("The details do not match the person who verified: name. This verification has been used up. "
                + "Verify the person again.", third.getMessage());
        IdentityVerification verification = stored.values().iterator().next();
        assertEquals(3, verification.getAttempts());
        assertNull(verification.getSalt(), "the hashes are gone");
        assertNull(verification.getNameHash());
        BadRequestException after = assertThrows(BadRequestException.class,
                () -> service.checkDetails(id, "Uwimana Claudine", LocalDate.of(1990, 5, 17), "0788123456"));
        assertTrue(after.getMessage().contains("Verify the person again"), after.getMessage());
    }

    @Test
    void theAttemptIsKeptEvenThoughTheRegistrationRollsBack() throws Exception {
        Transactional transactional = IdentityVerificationService.class
                .getMethod("checkDetails", Long.class, String.class, LocalDate.class, String.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
        assertEquals(List.of(IdentityMismatchException.class), List.of(transactional.noRollbackFor()));
    }

    @Test
    void anUnknownVerificationIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.checkDetails(99L, "Uwimana Claudine", LocalDate.of(1990, 5, 17), "0788123456"));

        assertEquals("Verification 99 does not exist.", e.getMessage());
    }

    @Test
    void aVerificationThatDidNotVerifyAnyoneIsRefused() {
        service.start(manager);

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.checkDetails(9L, "Uwimana Claudine", LocalDate.of(1990, 5, 17), "0788123456"));

        assertEquals("Verification 9 did not verify anyone.", e.getMessage());
    }

    @Test
    void anOldVerificationIsRefused() {
        Long id = verified();
        ReflectionTestUtils.setField(stored.values().iterator().next(), "verifiedAt",
                Instant.now().minus(Duration.ofMinutes(31)));

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.checkDetails(id, "Uwimana Claudine", LocalDate.of(1990, 5, 17), "0788123456"));

        assertTrue(e.getMessage().contains("more than 30 minutes old"), e.getMessage());
    }

    @Test
    void aVerificationWhoseHashesWereClearedIsRefused() {
        Long id = verified();
        stored.values().iterator().next().clearFingerprints();

        assertThrows(BadRequestException.class,
                () -> service.checkDetails(id, "Uwimana Claudine", LocalDate.of(1990, 5, 17), "0788123456"));
    }

    // --- Registering the member with the verification

    @Test
    void attachMarksTheNewMemberVerifiedAndClearsTheHashes() {
        Long id = verified();
        Member member = new Member();

        service.attach(id, member);

        assertTrue(member.isIdentityVerified());
        assertEquals(VerificationMethod.PASSWORD, member.getVerificationMethod());
        assertEquals("pairwise-sub", member.getIdentityToken());
        assertNotNull(member.getVerifiedAt());
        IdentityVerification verification = stored.values().iterator().next();
        assertEquals(member, verification.getMember());
        assertNull(verification.getSalt());
        assertNull(verification.getNameHash());
        assertNull(verification.getBirthHash());
        assertNull(verification.getPhoneHash());
    }

    @Test
    void aVerificationRegistersOnlyOneMember() {
        Long id = verified();
        service.attach(id, new Member());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.attach(id, new Member()));

        assertEquals("Verification 9 was already used to register a member.", e.getMessage());
    }

    @Test
    void aPersonWhoBecameAMemberInTheMeantimeIsRefused() {
        Long id = verified();
        when(memberRepository.existsByIdentityToken("pairwise-sub")).thenReturn(true);
        Member member = new Member();

        assertThrows(DuplicateIdentityException.class, () -> service.attach(id, member));

        assertFalse(member.isIdentityVerified());
    }

    @Test
    void attachRefusesAVerificationThatIsTooOldOrNotVerified() {
        Long id = verified();
        ReflectionTestUtils.setField(stored.values().iterator().next(), "verifiedAt",
                Instant.now().minus(Duration.ofMinutes(31)));

        assertThrows(BadRequestException.class, () -> service.attach(id, new Member()));
    }

    private Long verified() {
        String state = service.start(manager).state();
        return service.complete("the-code", state, manager).verificationId();
    }

    @Test
    void clearExpiredDropsOldVerifiersAndFingerprints() {
        when(verificationRepository.clearUnusedVerifiers(any(Instant.class))).thenReturn(2);
        when(verificationRepository.clearOldDetails(any(Instant.class))).thenReturn(1);

        assertEquals(3, service.clearExpired());

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(verificationRepository).clearOldDetails(cutoff.capture());
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
