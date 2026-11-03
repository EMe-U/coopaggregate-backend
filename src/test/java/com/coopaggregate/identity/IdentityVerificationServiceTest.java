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
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

@ExtendWith(OutputCaptureExtension.class)
class IdentityVerificationServiceTest {

    private static final MosipClaims CLAIMS = new MosipClaims("pairwise-sub", "Uwimana Claudine",
            "+250788123456");

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
    void theVerifiedNameAndPhoneAreKeptAndReturnedToTheManager() {
        String state = service.start(manager).state();

        VerifyResultResponse result = service.complete("the-code", state, manager);

        assertEquals("Uwimana Claudine", result.fullName());
        assertEquals("+250788123456", result.phone());
        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        assertEquals("Uwimana Claudine", verification.getVerifiedName());
        assertEquals("+250788123456", verification.getVerifiedPhone());
        assertNull(verification.getSalt(), "no fingerprints any more");
        assertNull(verification.getNameHash());
    }

    @Test
    void theNameIsTrimmedAndThePhoneNormalised() {
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", "  UWIMANA   Claudine \n", " 0788 123 456 "));
        String state = service.start(manager).state();

        VerifyResultResponse result = service.complete("the-code", state, manager);

        assertEquals("UWIMANA Claudine", result.fullName());
        assertEquals("+250788123456", result.phone());
    }

    @Test
    void theBirthDateAndEmailAreNeverKept() {
        String state = service.start(manager).state();

        service.complete("the-code", state, manager);

        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        String everything = String.join(" ", verification.getVerifiedName(), verification.getVerifiedPhone(),
                verification.getIdentityToken(), verification.getNonce());
        assertFalse(everything.contains("1990"));
        assertFalse(everything.contains("claudine@example.com"));
        assertNull(verification.getBirthHash());
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
        assertNull(verification.getVerifiedName(), "nothing is kept for a person who cannot register");
        assertNull(verification.getVerifiedPhone());
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
    void aProviderThatLeavesOutThePhoneVerifiesNobody(CapturedOutput output) {
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", "Uwimana Claudine", null));
        String state = service.start(manager).state();

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.complete("the-code", state, manager));

        assertEquals("eSignet did not share the member's name and phone number. Start the verification again and "
                + "allow both.", e.getMessage());
        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        assertFalse(verification.isVerified());
        assertNotNull(verification.getUsedAt());
        assertNull(verification.getVerifiedName());
        assertEquals(AuditAction.IDENTITY_VERIFICATION_FAILED, lastAudit().getAction());
        assertEquals("Provider MOSIP: missing details: phone", lastAudit().getDetails());
        assertFalse(output.getAll().contains("Uwimana"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"   ", "\t\n"})
    void aMissingOrBlankNameVerifiesNobody(String name) {
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", name, "+250788123456"));
        String state = service.start(manager).state();

        assertThrows(BadRequestException.class, () -> service.complete("the-code", state, manager));

        assertEquals("Provider MOSIP: missing details: name", lastAudit().getDetails());
        assertFalse(stored.get(IdentityVerificationService.sha256(state)).isVerified());
    }

    @Test
    void bothMissingAreNamedInTheHistory() {
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", null, " "));
        String state = service.start(manager).state();

        assertThrows(BadRequestException.class, () -> service.complete("the-code", state, manager));

        assertEquals("Provider MOSIP: missing details: name, phone", lastAudit().getDetails());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0711111111", "+256772123456", "788123456", "07881234567"})
    void aPhoneThatIsNotARwandanMobileNumberVerifiesNobody(String phone, CapturedOutput output) {
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", "Uwimana Claudine", phone));
        String state = service.start(manager).state();

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.complete("the-code", state, manager));

        assertEquals("The verified phone number is not a Rwandan mobile number, so the member cannot be registered "
                + "with it.", e.getMessage());
        assertEquals("Provider MOSIP: the phone is not a Rwandan mobile number", lastAudit().getDetails());
        assertFalse(stored.get(IdentityVerificationService.sha256(state)).isVerified());
        assertFalse(output.getAll().contains(phone));
    }

    @Test
    void aNameLongerThan150CharactersVerifiesNobody() {
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", "A".repeat(151), "+250788123456"));
        String state = service.start(manager).state();

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.complete("the-code", state, manager));

        assertEquals("The verified name is longer than 150 characters, so it cannot be saved.", e.getMessage());
        assertEquals("Provider MOSIP: the name is longer than 150 characters", lastAudit().getDetails());
    }

    @Test
    void aNameOfExactly150CharactersIsKept() {
        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", "A".repeat(150), "+250788123456"));
        String state = service.start(manager).state();

        assertEquals(150, service.complete("the-code", state, manager).fullName().length());
    }

    @Test
    void stateIsUsedUpAndHistoryKeptEvenWhenTheVerificationFails() throws Exception {
        Transactional transactional = IdentityVerificationService.class
                .getMethod("complete", String.class, String.class, Manager.class)
                .getAnnotation(Transactional.class);

        assertEquals(List.of(IdentityProviderException.class, DuplicateIdentityException.class,
                BadRequestException.class), List.of(transactional.noRollbackFor()));
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

    // --- Coming back from eSignet with an error

    @Test
    void aMemberWhoDoesNotAllowTheNameAndPhoneEndsTheVerification() {
        String state = service.start(manager).state();

        CancelResponse response = service.cancel(state, "essential_consent_denied", manager);

        assertEquals(CancelResponse.Reason.CLAIMS_REFUSED, response.reason());
        assertEquals("The member did not allow eSignet to share their name and phone number, which are needed to "
                + "register them. Nothing was saved.", response.message());
        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        assertNotNull(verification.getUsedAt());
        assertNull(verification.getCodeVerifier());
        assertEquals(AuditAction.IDENTITY_VERIFICATION_FAILED, lastAudit().getAction());
        assertEquals("Provider MOSIP: the member did not allow the name and phone (essential_consent_denied)",
                lastAudit().getDetails());
        verify(client, never()).fetchClaims(anyString(), anyString(), anyString());
    }

    @Test
    void aMemberWhoCancelsEndsTheVerification() {
        String state = service.start(manager).state();

        CancelResponse response = service.cancel(state, " ACCESS_DENIED ", manager);

        assertEquals(CancelResponse.Reason.CANCELLED, response.reason());
        assertEquals("The member cancelled the verification at eSignet. Nothing was saved.", response.message());
        assertEquals("Provider MOSIP: cancelled at the provider (access_denied)", lastAudit().getDetails());
    }

    @Test
    void anUnknownErrorIsNeverRecordedAsSent(CapturedOutput output) {
        String state = service.start(manager).state();

        CancelResponse response = service.cancel(state, "<script>Uwimana 0788123456</script>", manager);

        assertEquals(CancelResponse.Reason.PROVIDER_ERROR, response.reason());
        assertEquals("Provider MOSIP: the provider returned an error", lastAudit().getDetails());
        assertFalse(output.getAll().contains("script"));
        assertFalse(output.getAll().contains("Uwimana"));
        assertFalse(output.getAll().contains(state));
    }

    @Test
    void aStateThatWasCancelledCannotBeCompletedAfterwards() {
        String state = service.start(manager).state();
        service.cancel(state, "access_denied", manager);

        IdentityConflictException e = assertThrows(IdentityConflictException.class,
                () -> service.complete("the-code", state, manager));

        assertEquals("VERIFICATION_USED", e.errorCode());
    }

    @Test
    void anUnknownUsedOrOtherManagersStateGetsTheSameAnswerAndChangesNothing() {
        String state = service.start(manager).state();
        String used = service.start(manager).state();
        service.cancel(used, "access_denied", manager);
        org.mockito.Mockito.clearInvocations(auditLogRepository);

        CancelResponse unknown = service.cancel("made-up-state", "access_denied", manager);
        CancelResponse again = service.cancel(used, "access_denied", manager);
        CancelResponse other = service.cancel(state, "access_denied", manager(2L));

        assertEquals(CancelResponse.Reason.CANCELLED, unknown.reason());
        assertEquals(unknown, again);
        assertEquals(unknown, other);
        assertNull(stored.get(IdentityVerificationService.sha256(state)).getUsedAt());
        verify(auditLogRepository, never()).save(any());
    }

    // --- Error codes

    @Test
    void everyRefusalCarriesItsCode() {
        String expired = service.start(manager).state();
        stored.get(IdentityVerificationService.sha256(expired)).setExpiresAt(Instant.now().minusSeconds(1));
        assertEquals("VERIFICATION_EXPIRED", assertThrows(IdentityRequestException.class,
                () -> service.complete("the-code", expired, manager)).errorCode());
        assertEquals("VERIFICATION_UNKNOWN", assertThrows(IdentityRequestException.class,
                () -> service.complete("the-code", "made-up", manager)).errorCode());
        assertEquals("VERIFICATION_UNKNOWN", assertThrows(IdentityRequestException.class,
                () -> service.verifiedDetails(99L, manager)).errorCode());

        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", null, "+250788123456"));
        String missing = service.start(manager).state();
        assertEquals("CLAIMS_MISSING", assertThrows(IdentityRequestException.class,
                () -> service.complete("the-code", missing, manager)).errorCode());

        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", "Uwimana Claudine", "0711111111"));
        String phone = service.start(manager).state();
        assertEquals("PHONE_NOT_RWANDAN", assertThrows(IdentityRequestException.class,
                () -> service.complete("the-code", phone, manager)).errorCode());

        when(client.fetchClaims(anyString(), anyString(), anyString())).thenReturn(
                new MosipClaims("pairwise-sub", "A".repeat(151), "+250788123456"));
        String name = service.start(manager).state();
        assertEquals("NAME_TOO_LONG", assertThrows(IdentityRequestException.class,
                () -> service.complete("the-code", name, manager)).errorCode());

        when(memberRepository.existsByIdentityToken("pairwise-sub")).thenReturn(true);
        String duplicate = service.start(manager).state();
        assertEquals("ALREADY_MEMBER", assertThrows(DuplicateIdentityException.class,
                () -> service.complete("the-code", duplicate, manager)).errorCode());
    }

    @Test
    void theRegistrationRefusalsCarryTheirCodes() {
        Long id = verified();
        ReflectionTestUtils.setField(stored.values().iterator().next(), "verifiedAt",
                Instant.now().minus(Duration.ofMinutes(31)));
        assertEquals("VERIFICATION_EXPIRED", assertThrows(IdentityRequestException.class,
                () -> service.verifiedDetails(id, manager)).errorCode());

        ReflectionTestUtils.setField(stored.values().iterator().next(), "verifiedAt", Instant.now());
        service.attach(id, manager, new Member());
        assertEquals("VERIFICATION_USED", assertThrows(IdentityConflictException.class,
                () -> service.verifiedDetails(id, manager)).errorCode());
    }

    // --- Registering the member with the verification

    @Test
    void theVerifiedDetailsAreGivenToTheManagerWhoVerified() {
        Long id = verified();

        VerifiedDetails details = service.verifiedDetails(id, manager);

        assertEquals("Uwimana Claudine", details.fullName());
        assertEquals("+250788123456", details.phone());
        assertNotNull(stored.values().iterator().next().getVerifiedName(), "kept until the member is saved");
    }

    @Test
    void anotherManagerCannotUseTheVerification() {
        Long id = verified();

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.verifiedDetails(id, manager(2L)));
        assertThrows(BadRequestException.class, () -> service.attach(id, manager(2L), new Member()));

        assertEquals("Unknown verification. Verify the person again.", e.getMessage());
        assertNull(stored.values().iterator().next().getMember());
    }

    @Test
    void anUnknownVerificationIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.verifiedDetails(99L, manager));

        assertEquals("Unknown verification. Verify the person again.", e.getMessage());
    }

    @Test
    void aVerificationThatDidNotVerifyAnyoneIsRefused() {
        service.start(manager);

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.verifiedDetails(9L, manager));

        assertEquals("Unknown verification. Verify the person again.", e.getMessage());
    }

    @Test
    void anOldVerificationIsRefused() {
        Long id = verified();
        ReflectionTestUtils.setField(stored.values().iterator().next(), "verifiedAt",
                Instant.now().minus(Duration.ofMinutes(31)));

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.verifiedDetails(id, manager));

        assertEquals("This verification is more than 30 minutes old. Verify the person again.", e.getMessage());
        assertThrows(BadRequestException.class, () -> service.attach(id, manager, new Member()));
    }

    @Test
    void aVerificationWhoseDetailsWereClearedIsRefused() {
        Long id = verified();
        stored.values().iterator().next().clearVerifiedDetails();

        assertThrows(BadRequestException.class, () -> service.verifiedDetails(id, manager));
    }

    @Test
    void attachMarksTheNewMemberVerifiedAndClearsTheDetails() {
        Long id = verified();
        Member member = new Member();

        service.attach(id, manager, member);

        assertTrue(member.isIdentityVerified());
        assertEquals(VerificationMethod.PASSWORD, member.getVerificationMethod());
        assertEquals("pairwise-sub", member.getIdentityToken());
        assertNotNull(member.getVerifiedAt());
        IdentityVerification verification = stored.values().iterator().next();
        assertEquals(member, verification.getMember());
        assertNull(verification.getVerifiedName());
        assertNull(verification.getVerifiedPhone());
    }

    @Test
    void aVerificationRegistersOnlyOneMember() {
        Long id = verified();
        service.attach(id, manager, new Member());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.attach(id, manager, new Member()));
        IllegalStateException again = assertThrows(IllegalStateException.class,
                () -> service.verifiedDetails(id, manager));

        assertEquals("This verification was already used to register a member. Verify the next person again.",
                e.getMessage());
        assertEquals(e.getMessage(), again.getMessage());
    }

    @Test
    void aPersonWhoBecameAMemberInTheMeantimeIsRefused() {
        Long id = verified();
        when(memberRepository.existsByIdentityToken("pairwise-sub")).thenReturn(true);
        Member member = new Member();

        assertThrows(DuplicateIdentityException.class, () -> service.attach(id, manager, member));

        assertFalse(member.isIdentityVerified());
    }

    @Test
    void registeringNeverLogsTheDetails(CapturedOutput output) {
        Long id = verified();

        service.verifiedDetails(id, manager);
        service.attach(id, manager, new Member());

        assertFalse(output.getAll().contains("Uwimana"));
        assertFalse(output.getAll().contains("788123456"));
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
