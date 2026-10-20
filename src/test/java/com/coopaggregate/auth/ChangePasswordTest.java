package com.coopaggregate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.manager.ManagerRepository;

class ChangePasswordTest {

    private static final String EMAIL = "manager@example.com";
    private static final String PASSWORD = "old-password";

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private ManagerRepository managerRepository;
    private AuditLogRepository auditLogRepository;
    private AuthService authService;
    private Manager manager;

    @BeforeEach
    void setUp() {
        manager = new Manager();
        manager.setEmail(EMAIL);
        manager.setPasswordHash(passwordEncoder.encode(PASSWORD));
        managerRepository = mock(ManagerRepository.class);
        when(managerRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(manager));
        auditLogRepository = mock(AuditLogRepository.class);
        authService = new AuthService(managerRepository, passwordEncoder, mock(JwtEncoder.class),
                new LoginAttemptService(), auditLogRepository);
    }

    @Test
    void newPasswordIsSavedHashedAndRecordedWithoutThePassword() {
        authService.changePassword(EMAIL, new ChangePasswordRequest(PASSWORD, "new-password"));

        assertTrue(passwordEncoder.matches("new-password", manager.getPasswordHash()));
        verify(managerRepository).save(manager);
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals(AuditAction.PASSWORD_CHANGED, audit.getValue().getAction());
        assertNull(audit.getValue().getDetails());
    }

    @Test
    void wrongCurrentPasswordIsRejected() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> authService.changePassword(
                EMAIL, new ChangePasswordRequest("wrong-password", "new-password")));

        assertEquals("The current password is incorrect.", error.getMessage());
        assertTrue(passwordEncoder.matches(PASSWORD, manager.getPasswordHash()));
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void newPasswordMustDifferFromTheCurrentOne() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> authService.changePassword(
                EMAIL, new ChangePasswordRequest(PASSWORD, PASSWORD)));

        assertEquals("The new password must be different from the current password.", error.getMessage());
        verify(managerRepository, never()).save(any());
    }

    @Test
    void passwordOverBcryptLimitIsRejected() {
        String tooManyBytes = "é".repeat(40);

        assertThrows(BadRequestException.class, () -> authService.changePassword(
                EMAIL, new ChangePasswordRequest(PASSWORD, tooManyBytes)));
        verify(managerRepository, never()).save(any());
    }
}
