package com.coopaggregate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.manager.ManagerRepository;

class AuthServiceTest {

    private static final String EMAIL = "manager@example.com";
    private static final String PASSWORD = "correct-password";

    private AuthService authService;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        Manager manager = new Manager();
        manager.setName("Test Manager");
        manager.setEmail(EMAIL);
        manager.setPasswordHash(passwordEncoder.encode(PASSWORD));

        ManagerRepository managerRepository = mock(ManagerRepository.class);
        when(managerRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(manager));
        when(managerRepository.findByEmailIgnoreCase("unknown@example.com")).thenReturn(Optional.empty());

        JwtConfig jwtConfig = new JwtConfig("test-secret-that-is-at-least-32-characters");
        jwtDecoder = jwtConfig.jwtDecoder();
        authService = new AuthService(managerRepository, passwordEncoder, jwtConfig.jwtEncoder(),
                new LoginAttemptService());
    }

    @Test
    void correctLoginReturnsValidToken() {
        LoginResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        Jwt jwt = jwtDecoder.decode(response.token());
        assertEquals(EMAIL, jwt.getSubject());
        assertEquals("Test Manager", response.managerName());
        Duration validity = Duration.between(Instant.now(), response.expiresAt());
        assertTrue(validity.compareTo(Duration.ofHours(11)) > 0 && validity.compareTo(Duration.ofHours(12)) <= 0);
    }

    @Test
    void wrongPasswordIsRejected() {
        InvalidLoginException error = assertThrows(InvalidLoginException.class,
                () -> authService.login(new LoginRequest(EMAIL, "wrong-password")));

        assertEquals("Invalid email or password", error.getMessage());
    }

    @Test
    void unknownEmailGetsSameMessageAsWrongPassword() {
        InvalidLoginException error = assertThrows(InvalidLoginException.class,
                () -> authService.login(new LoginRequest("unknown@example.com", PASSWORD)));

        assertEquals("Invalid email or password", error.getMessage());
    }

    @Test
    void emailIsLockedAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            assertThrows(InvalidLoginException.class,
                    () -> authService.login(new LoginRequest(EMAIL, "wrong-password")));
        }

        assertThrows(TooManyLoginAttemptsException.class,
                () -> authService.login(new LoginRequest(EMAIL, PASSWORD)));
    }

    @Test
    void successfulLoginResetsFailureCount() {
        for (int i = 0; i < 4; i++) {
            assertThrows(InvalidLoginException.class,
                    () -> authService.login(new LoginRequest(EMAIL, "wrong-password")));
        }
        authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThrows(InvalidLoginException.class,
                () -> authService.login(new LoginRequest(EMAIL, "wrong-password")));
        authService.login(new LoginRequest(EMAIL, PASSWORD));
    }
}
