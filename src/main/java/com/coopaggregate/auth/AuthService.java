package com.coopaggregate.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.error.BadRequestException;

import com.coopaggregate.manager.Manager;
import com.coopaggregate.manager.ManagerRepository;

@Service
public class AuthService {

    private static final Duration TOKEN_VALIDITY = Duration.ofHours(12);

    private final ManagerRepository managerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final LoginAttemptService loginAttemptService;
    private final AuditLogRepository auditLogRepository;

    public AuthService(ManagerRepository managerRepository, PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder, LoginAttemptService loginAttemptService,
                       AuditLogRepository auditLogRepository) {
        this.managerRepository = managerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.loginAttemptService = loginAttemptService;
        this.auditLogRepository = auditLogRepository;
    }

    public LoginResponse login(LoginRequest request) {
        if (loginAttemptService.isLocked(request.email())) {
            throw new TooManyLoginAttemptsException();
        }

        Manager manager = managerRepository.findByEmailIgnoreCase(request.email())
                .filter(m -> passwordEncoder.matches(request.password(), m.getPasswordHash()))
                .orElse(null);
        if (manager == null) {
            loginAttemptService.recordFailure(request.email());
            throw new InvalidLoginException();
        }
        loginAttemptService.recordSuccess(request.email());

        Instant expiresAt = Instant.now().plus(TOKEN_VALIDITY);
        return new LoginResponse(createToken(manager, expiresAt), expiresAt, manager.getName());
    }

    public Manager currentManager(String email) {
        return managerRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("Manager account not found."));
    }

    /** The history records that the password changed, never the password itself. */
    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        Manager manager = currentManager(email);
        if (!passwordEncoder.matches(request.currentPassword(), manager.getPasswordHash())) {
            throw new BadRequestException("The current password is incorrect.");
        }
        if (request.newPassword().equals(request.currentPassword())) {
            throw new BadRequestException("The new password must be different from the current password.");
        }
        // Letters like é take two bytes, so 72 characters can still be too long for BCrypt.
        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadRequestException("The new password is too long.");
        }
        manager.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        managerRepository.save(manager);
        auditLogRepository.save(AuditLog.of(AuditAction.PASSWORD_CHANGED, null, manager, null));
    }

    private String createToken(Manager manager, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(manager.getEmail())
                .issuedAt(Instant.now())
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
