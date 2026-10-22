package com.coopaggregate.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

/**
 * Verifies a new member's identity before registration: start gives a random one-use state and where to send the
 * member; complete checks the state, asks the verifier who the person is and refuses people who are already
 * members. Codes, states and tokens are never logged or returned.
 */
@Service
public class IdentityVerificationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerificationService.class);
    static final Duration STATE_VALIDITY = Duration.ofMinutes(10);
    static final Duration USE_WITHIN = Duration.ofMinutes(30);

    private final SecureRandom random = new SecureRandom();
    private final IdentityVerificationRepository verificationRepository;
    private final MemberRepository memberRepository;
    private final AuditLogRepository auditLogRepository;
    private final IdentityVerifier verifier;

    public IdentityVerificationService(IdentityVerificationRepository verificationRepository,
                                       MemberRepository memberRepository, AuditLogRepository auditLogRepository,
                                       IdentityVerifier verifier) {
        this.verificationRepository = verificationRepository;
        this.memberRepository = memberRepository;
        this.auditLogRepository = auditLogRepository;
        this.verifier = verifier;
    }

    @Transactional
    public StartVerificationResponse start(Manager manager) {
        String state = randomToken();
        String nonce = randomToken();
        Instant expiresAt = Instant.now().plus(STATE_VALIDITY);
        String authorizationUrl = verifier.authorizationUrl(state, nonce);

        IdentityVerification verification = new IdentityVerification();
        verification.setStateHash(sha256(state));
        verification.setNonce(nonce);
        verification.setManager(manager);
        verification.setExpiresAt(expiresAt);
        verificationRepository.save(verification);
        return new StartVerificationResponse(verifier.name(), authorizationUrl, state, expiresAt);
    }

    /**
     * Completes a verification. The state is used up even when the provider fails or the person is already a
     * member, and the result is written to the change history either way.
     */
    @Transactional(noRollbackFor = {IdentityProviderException.class, DuplicateIdentityException.class})
    public VerifyResultResponse complete(String code, String state, Manager manager) {
        IdentityVerification verification = verificationRepository.findForUpdateByStateHash(sha256(state))
                .filter(found -> Objects.equals(found.getManager().getId(), manager.getId()))
                .orElseThrow(() -> new BadRequestException("Unknown verification. Start the verification again."));
        if (verification.getUsedAt() != null) {
            throw new IllegalStateException("This verification was already used. Start the verification again.");
        }
        Instant now = Instant.now();
        verification.markUsed(now);
        if (now.isAfter(verification.getExpiresAt())) {
            throw new BadRequestException("This verification has expired. Start the verification again.");
        }

        VerifiedIdentity identity;
        try {
            identity = verifier.complete(code, verification.getNonce());
        } catch (IdentityProviderException e) {
            log.warn("Identity verification {} failed: {}", verification.getId(), e.getMessage());
            record(AuditAction.IDENTITY_VERIFICATION_FAILED, manager, "Provider " + verifier.name()
                    + (e.isNotConfigured() ? ": not configured" : ": provider error"));
            throw e;
        }

        if (memberRepository.existsByIdentityToken(identity.subject())) {
            record(AuditAction.IDENTITY_DUPLICATE, manager, "Provider " + verifier.name() + ", method "
                    + identity.method() + ": the person is already a member");
            throw new DuplicateIdentityException();
        }
        verification.markVerified(identity, now);
        record(AuditAction.IDENTITY_VERIFIED, manager,
                "Provider " + verifier.name() + ", method " + identity.method());
        return new VerifyResultResponse(verification.getId(), true, identity.method(), now.plus(USE_WITHIN));
    }

    /**
     * Marks a newly registered member as verified with a completed verification. A verification gives one member
     * only, must be used within 30 minutes, and is refused if the person became a member in the meantime.
     */
    @Transactional
    public void attach(Long verificationId, Member member) {
        IdentityVerification verification = verificationRepository.findForUpdateById(verificationId)
                .orElseThrow(() -> new BadRequestException("Verification " + verificationId + " does not exist."));
        if (!verification.isVerified()) {
            throw new BadRequestException("Verification " + verificationId + " did not verify anyone.");
        }
        if (verification.getMember() != null) {
            throw new IllegalStateException("Verification " + verificationId
                    + " was already used to register a member.");
        }
        if (Instant.now().isAfter(verification.getVerifiedAt().plus(USE_WITHIN))) {
            throw new BadRequestException("Verification " + verificationId
                    + " is more than 30 minutes old. Verify the person again.");
        }
        if (memberRepository.existsByIdentityToken(verification.getIdentityToken())) {
            throw new DuplicateIdentityException();
        }
        member.markIdentityVerified(verification.getVerificationMethod(), verification.getVerifiedAt(),
                verification.getIdentityToken());
        verification.setMember(member);
    }

    // No member is linked yet and the details hold no personal data: only the provider and the method.
    private void record(AuditAction action, Manager manager, String details) {
        auditLogRepository.save(AuditLog.of(action, null, manager, details));
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
