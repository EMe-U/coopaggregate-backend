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
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.MemberRepository;

/**
 * Verifies a new member's identity before registration: start gives a random one-use state and where to send the
 * member; complete checks the state, asks eSignet who the person is and refuses people who are already members.
 * Codes, states, tokens and the provider's claims are never logged or returned; of the claims only salted hashes of
 * the name, birth date and phone are kept, until the member is registered or 30 minutes have passed.
 */
@Service
public class IdentityVerificationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerificationService.class);
    static final String PROVIDER = "MOSIP";
    static final Duration STATE_VALIDITY = Duration.ofMinutes(10);
    static final Duration USE_WITHIN = Duration.ofMinutes(30);

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
    @Transactional(noRollbackFor = {IdentityProviderException.class, DuplicateIdentityException.class})
    public VerifyResultResponse complete(String code, String state, Manager manager) {
        IdentityVerification verification = verificationRepository.findForUpdateByStateHash(sha256(state))
                .filter(found -> Objects.equals(found.getManager().getId(), manager.getId()))
                .orElseThrow(() -> new BadRequestException("Unknown verification. Start the verification again."));
        if (verification.getUsedAt() != null) {
            throw new IllegalStateException("This verification was already used. Start the verification again.");
        }
        Instant now = Instant.now();
        String codeVerifier = verification.getCodeVerifier();
        verification.markUsed(now);
        if (now.isAfter(verification.getExpiresAt())) {
            throw new BadRequestException("This verification has expired. Start the verification again.");
        }

        MosipClaims claims;
        try {
            claims = client().fetchClaims(code, codeVerifier, verification.getNonce());
        } catch (IdentityProviderException e) {
            log.warn("Identity verification {} failed: {}", verification.getId(), e.getMessage());
            record(AuditAction.IDENTITY_VERIFICATION_FAILED, manager, "Provider " + PROVIDER
                    + (e.isNotConfigured() ? ": not configured" : ": provider error"));
            throw e;
        }

        if (memberRepository.existsByIdentityToken(claims.subject())) {
            record(AuditAction.IDENTITY_DUPLICATE, manager, "Provider " + PROVIDER + ", method "
                    + VerificationMethod.PASSWORD + ": the person is already a member");
            throw new DuplicateIdentityException();
        }

        String name = ClaimFingerprints.normalizeName(claims.name());
        String birth = ClaimFingerprints.normalizeBirth(claims.birthdate());
        String phone = ClaimFingerprints.normalizePhone(claims.phoneNumber());
        List<String> missing = new ArrayList<>();
        if (name == null) {
            missing.add("name");
        }
        if (birth == null) {
            missing.add("birthDate");
        }
        if (phone == null) {
            missing.add("phone");
        }
        if (!missing.isEmpty()) {
            log.warn("Identity verification {} failed: the provider did not give {}", verification.getId(), missing);
            record(AuditAction.IDENTITY_VERIFICATION_FAILED, manager, "Provider " + PROVIDER
                    + ": missing or unreadable details: " + String.join(", ", missing));
            throw IdentityProviderException.failed("The identity provider did not give the name, birth date and "
                    + "phone number needed to check the member.");
        }

        String salt = ClaimFingerprints.newSalt();
        verification.setFingerprints(salt, ClaimFingerprints.hash(salt, ClaimFingerprints.NAME, name),
                ClaimFingerprints.hash(salt, ClaimFingerprints.BIRTH, birth),
                ClaimFingerprints.hash(salt, ClaimFingerprints.PHONE, phone));
        verification.markVerified(new VerifiedIdentity(claims.subject(), VerificationMethod.PASSWORD), now);
        record(AuditAction.IDENTITY_VERIFIED, manager,
                "Provider " + PROVIDER + ", method " + VerificationMethod.PASSWORD);
        return new VerifyResultResponse(verification.getId(), true, VerificationMethod.PASSWORD, now.plus(USE_WITHIN));
    }

    /**
     * Drops what is not needed any more: the PKCE verifier of a state that was never used, and the fingerprints of a
     * verification nobody registered a member with in time. Returns how many rows were cleaned.
     */
    @Transactional
    public int clearExpired() {
        Instant now = Instant.now();
        return verificationRepository.clearUnusedVerifiers(now)
                + verificationRepository.clearOldFingerprints(now.minus(USE_WITHIN));
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
