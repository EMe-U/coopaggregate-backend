package com.coopaggregate.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

/**
 * Verifies a new member's identity before registration: start gives a random one-use state and where to send the
 * member; complete checks the state, asks eSignet who the person is, refuses people who are already members, and keeps
 * the verified name and phone that the member is then registered with. Codes, states and tokens are never logged or
 * returned; the name and phone are returned to the manager once, never logged or written to the change history, and
 * kept only until the member is registered or 30 minutes have passed.
 */
@Service
public class IdentityVerificationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerificationService.class);
    static final String PROVIDER = "MOSIP";
    static final Duration STATE_VALIDITY = Duration.ofMinutes(10);
    static final Duration USE_WITHIN = Duration.ofMinutes(30);
    static final int MAX_NAME = 150;

    private final SecureRandom random = new SecureRandom();
    private final IdentityVerificationRepository verificationRepository;
    private final MemberRepository memberRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectProvider<MosipClient> clientProvider;

    public IdentityVerificationService(IdentityVerificationRepository verificationRepository,
                                       MemberRepository memberRepository, AuditLogRepository auditLogRepository,
                                       ObjectProvider<MosipClient> clientProvider) {
        this.verificationRepository = verificationRepository;
        this.memberRepository = memberRepository;
        this.auditLogRepository = auditLogRepository;
        this.clientProvider = clientProvider;
    }

    @Transactional
    public StartVerificationResponse start(Manager manager) {
        MosipClient client = client();
        String state = randomToken();
        String nonce = randomToken();
        String codeVerifier = Pkce.newVerifier();
        Instant expiresAt = Instant.now().plus(STATE_VALIDITY);
        String authorizationUrl = client.authorizationUrl(state, nonce, Pkce.challenge(codeVerifier));

        IdentityVerification verification = new IdentityVerification();
        verification.setStateHash(sha256(state));
        verification.setNonce(nonce);
        verification.setCodeVerifier(codeVerifier);
        verification.setManager(manager);
        verification.setExpiresAt(expiresAt);
        verificationRepository.save(verification);
        return new StartVerificationResponse(PROVIDER, authorizationUrl, state, expiresAt);
    }

    /**
     * Completes a verification. The state is used up even when the provider fails or the person is already a
     * member, and the result is written to the change history either way.
     */
    @Transactional(noRollbackFor = {IdentityProviderException.class, DuplicateIdentityException.class,
            BadRequestException.class})
    public VerifyResultResponse complete(String code, String state, Manager manager) {
        IdentityVerification verification = verificationRepository.findForUpdateByStateHash(sha256(state))
                .filter(found -> Objects.equals(found.getManager().getId(), manager.getId()))
                .orElseThrow(() -> new IdentityRequestException(IdentityErrorCode.VERIFICATION_UNKNOWN,
                        "Unknown verification. Start the verification again."));
        if (verification.getUsedAt() != null) {
            throw new IdentityConflictException(IdentityErrorCode.VERIFICATION_USED,
                    "This verification was already used. Start the verification again.");
        }
        Instant now = Instant.now();
        String codeVerifier = verification.getCodeVerifier();
        verification.markUsed(now);
        if (now.isAfter(verification.getExpiresAt())) {
            throw new IdentityRequestException(IdentityErrorCode.VERIFICATION_EXPIRED,
                    "This verification has expired. Start the verification again.");
        }

        MosipClaims claims;
        try {
            claims = client().fetchClaims(code, codeVerifier, verification.getNonce());
        } catch (IdentityProviderException e) {
            log.warn("Identity verification {} failed: {}{}", verification.getId(), e.getMessage(),
                    e.getDetail() == null ? "" : " [" + e.getDetail() + "]");
            record(AuditAction.IDENTITY_VERIFICATION_FAILED, manager, "Provider " + PROVIDER
                    + (e.isNotConfigured() ? ": not configured" : ": provider error"));
            throw e;
        }

        // How the person logged in at eSignet: when the id_token says so (acr), only an allowed way counts. The local
        // mock accepts any biometric capture, so by default only the one-time code does. Without an acr nothing
        // changes. The acr value is never logged or recorded.
        if (claims.acr() != null && !client().isAllowedAcr(claims.acr())) {
            refuse(verification, manager, IdentityErrorCode.ACR_NOT_ALLOWED,
                    "the login method (acr) is not in MOSIP_ALLOWED_ACRS",
                    "This way of logging in at eSignet is not accepted for verifying a member. Start the "
                            + "verification again and log in with the one-time code.");
        }

        if (memberRepository.existsByIdentityToken(claims.subject())) {
            record(AuditAction.IDENTITY_DUPLICATE, manager, "Provider " + PROVIDER + ", method "
                    + VerificationMethod.PASSWORD + ": the person is already a member");
            throw new DuplicateIdentityException();
        }

        // The name and phone become the member's own: they must be there and fit, and the phone must be Rwandan.
        String name = verifiedName(claims.name());
        String phone = claims.phoneNumber() == null ? null : claims.phoneNumber().replaceAll("\s+", "");
        List<String> missing = new ArrayList<>();
        if (name == null) {
            missing.add("name");
        }
        if (phone == null || phone.isEmpty()) {
            missing.add("phone");
        }
        if (!missing.isEmpty()) {
            refuse(verification, manager, IdentityErrorCode.CLAIMS_MISSING,
                    "missing details: " + String.join(", ", missing),
                    "eSignet did not share the member's name and phone number. Start the verification again and "
                            + "allow both.");
        }
        if (name.length() > MAX_NAME) {
            refuse(verification, manager, IdentityErrorCode.NAME_TOO_LONG,
                    "the name is longer than " + MAX_NAME + " characters",
                    "The verified name is longer than " + MAX_NAME + " characters, so it cannot be saved.");
        }
        if (!RwandanPhoneNumber.isValid(phone)) {
            refuse(verification, manager, IdentityErrorCode.PHONE_NOT_RWANDAN,
                    "the phone is not a Rwandan mobile number",
                    "The verified phone number is not a Rwandan mobile number, so the member cannot be registered "
                            + "with it.");
        }
        phone = RwandanPhoneNumber.normalize(phone);

        verification.setVerifiedDetails(name, phone);
        verification.markVerified(new VerifiedIdentity(claims.subject(), VerificationMethod.PASSWORD), now);
        record(AuditAction.IDENTITY_VERIFIED, manager,
                "Provider " + PROVIDER + ", method " + VerificationMethod.PASSWORD);
        return new VerifyResultResponse(verification.getId(), true, VerificationMethod.PASSWORD, name, phone,
                now.plus(USE_WITHIN));
    }

    /**
     * The verified name and phone to register the member with. Only the manager who started the verification can use
     * it, once, within 30 minutes. Nothing changes here; attach then links the member and clears the details.
     */
    @Transactional
    public VerifiedDetails verifiedDetails(Long verificationId, Manager manager) {
        IdentityVerification verification = usableVerification(verificationId, manager);
        return new VerifiedDetails(verification.getVerifiedName(), verification.getVerifiedPhone());
    }

    /**
     * Marks a newly registered member as verified with the verification its details came from. A verification gives
     * one member only, and is refused if the person became a member in the meantime. The verified details are
     * cleared: they are now the member's own.
     */
    @Transactional
    public void attach(Long verificationId, Manager manager, Member member) {
        IdentityVerification verification = usableVerification(verificationId, manager);
        if (memberRepository.existsByIdentityToken(verification.getIdentityToken())) {
            throw new DuplicateIdentityException();
        }
        member.markIdentityVerified(verification.getVerificationMethod(), verification.getVerifiedAt(),
                verification.getIdentityToken());
        verification.setMember(member);
        verification.clearVerifiedDetails();
    }

    // The verification, locked, when this manager can still use it to register a member. Another manager's
    // verification is answered like an unknown one.
    private IdentityVerification usableVerification(Long verificationId, Manager manager) {
        IdentityVerification verification = verificationRepository.findForUpdateById(verificationId)
                .filter(found -> Objects.equals(found.getManager().getId(), manager.getId()))
                .filter(IdentityVerification::isVerified)
                .orElseThrow(() -> new IdentityRequestException(IdentityErrorCode.VERIFICATION_UNKNOWN,
                        "Unknown verification. Verify the person again."));
        if (verification.getMember() != null) {
            throw new IdentityConflictException(IdentityErrorCode.VERIFICATION_USED,
                    "This verification was already used to register a member. Verify the next person again.");
        }
        if (Instant.now().isAfter(verification.getVerifiedAt().plus(USE_WITHIN))
                || !verification.hasVerifiedDetails()) {
            throw new IdentityRequestException(IdentityErrorCode.VERIFICATION_EXPIRED,
                    "This verification is more than 30 minutes old. Verify the person again.");
        }
        return verification;
    }

    // Spaces trimmed and runs of spaces made one; null when there is no name.
    private static String verifiedName(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.strip().replaceAll("\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    // Records why a verification verified nobody (the reason only, never the values) and refuses it.
    private void refuse(IdentityVerification verification, Manager manager, IdentityErrorCode code, String reason,
                        String message) {
        log.warn("Identity verification {} failed: {}", verification.getId(), reason);
        record(AuditAction.IDENTITY_VERIFICATION_FAILED, manager, "Provider " + PROVIDER + ": " + reason);
        throw new IdentityRequestException(code, message);
    }

    /**
     * The member came back from eSignet with an error instead of a code: they cancelled, refused to share the name and
     * phone, or eSignet failed. The state is used up and the outcome written to the change history. Only a known error
     * code is recorded; anything else, and eSignet's free-text description, never is. The answer is the same whether
     * or not the state is known, so it tells nothing about other verifications.
     */
    @Transactional
    public CancelResponse cancel(String state, String error, Manager manager) {
        String code = Diagnostics.knownError(error);
        CancelResponse.Reason reason = CancelResponse.reasonFor(code);
        verificationRepository.findForUpdateByStateHash(sha256(state))
                .filter(found -> Objects.equals(found.getManager().getId(), manager.getId()))
                .filter(found -> found.getUsedAt() == null)
                .ifPresent(verification -> {
                    verification.markUsed(Instant.now());
                    log.info("Identity verification {} ended at the provider: {}", verification.getId(),
                            code == null ? "unknown error" : code);
                    record(AuditAction.IDENTITY_VERIFICATION_FAILED, manager, "Provider " + PROVIDER + ": "
                            + reason.history() + (code == null ? "" : " (" + code + ")"));
                });
        return CancelResponse.of(reason);
    }

    /**
     * Drops what is not needed any more: the PKCE verifier of a state that was never used, and the verified details of a
     * verification nobody registered a member with in time. Returns how many rows were cleaned.
     */
    @Transactional
    public int clearExpired() {
        Instant now = Instant.now();
        return verificationRepository.clearUnusedVerifiers(now)
                + verificationRepository.clearOldDetails(now.minus(USE_WITHIN));
    }

    // No member is linked yet and the details hold no personal data: only the provider, the method and field names.
    private void record(AuditAction action, Manager manager, String details) {
        auditLogRepository.save(AuditLog.of(action, null, manager, details));
    }

    private MosipClient client() {
        MosipClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw IdentityProviderException.notConfigured("Identity verification is not available.");
        }
        return client;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
