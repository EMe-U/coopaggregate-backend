package com.coopaggregate.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.coopaggregate.manager.Manager;
import com.coopaggregate.manager.ManagerRepository;

@Service
public class AuthService {

    private static final Duration TOKEN_VALIDITY = Duration.ofHours(12);

    private final ManagerRepository managerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    public AuthService(ManagerRepository managerRepository, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder) {
        this.managerRepository = managerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
    }

    public LoginResponse login(LoginRequest request) {
        Manager manager = managerRepository.findByEmailIgnoreCase(request.email())
                .filter(m -> passwordEncoder.matches(request.password(), m.getPasswordHash()))
                .orElseThrow(InvalidLoginException::new);

        Instant expiresAt = Instant.now().plus(TOKEN_VALIDITY);
        return new LoginResponse(createToken(manager, expiresAt), expiresAt, manager.getName());
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
