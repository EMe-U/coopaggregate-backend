package com.coopaggregate.identity;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, Long> {

    // SELECT ... FOR UPDATE: the same state sent twice at once is still only used once.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<IdentityVerification> findForUpdateByStateHash(String stateHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<IdentityVerification> findForUpdateById(Long id);
}
