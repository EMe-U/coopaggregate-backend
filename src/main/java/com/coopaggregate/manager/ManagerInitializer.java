package com.coopaggregate.manager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class ManagerInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ManagerInitializer.class);

    private final ManagerRepository managerRepository;
    private final PasswordEncoder passwordEncoder;
    private final String name;
    private final String email;
    private final String password;

    public ManagerInitializer(ManagerRepository managerRepository,
                              PasswordEncoder passwordEncoder,
                              @Value("${app.manager.name}") String name,
                              @Value("${app.manager.email}") String email,
                              @Value("${app.manager.password}") String password) {
        this.managerRepository = managerRepository;
        this.passwordEncoder = passwordEncoder;
        this.name = name;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (managerRepository.count() > 0) {
            return;
        }
        if (name.isBlank() || email.isBlank() || password.isBlank()) {
            log.warn("No manager account exists and MANAGER_NAME, MANAGER_EMAIL or MANAGER_PASSWORD is not set. "
                    + "Skipping manager creation.");
            return;
        }

        Manager manager = new Manager();
        manager.setName(name);
        manager.setEmail(email);
        manager.setPasswordHash(passwordEncoder.encode(password));
        managerRepository.save(manager);
        log.info("Created manager account for {}", email);
    }
}
