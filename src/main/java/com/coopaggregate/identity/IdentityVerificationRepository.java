package com.coopaggregate.identity;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, Long> {

    // SELECT ... FOR UPDATE: the same state sent twice at once is still only used once.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<IdentityVerification> findForUpdateByStateHash(String stateHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<IdentityVerification> findForUpdateById(Long id);

    // The PKCE verifier of a state that expired without being used.
    @Modifying
    @Query("update IdentityVerification v set v.codeVerifier = null "
            + "where v.codeVerifier is not null and v.expiresAt < :now")
    int clearUnusedVerifiers(@Param("now") Instant now);

    // Every verified detail of a verification nobody registered a member with in time. A row with any detail left is
    // cleared, so nothing personal stays even if only an optional detail were set.
    @Modifying
    @Query("update IdentityVerification v set v.verifiedName = null, v.verifiedPhone = null, "
            + "v.verifiedBirthdate = null, v.verifiedGender = null, v.verifiedEmail = null, v.verifiedAddress = null "
            + "where (v.verifiedName is not null or v.verifiedPhone is not null or v.verifiedBirthdate is not null "
            + "or v.verifiedGender is not null or v.verifiedEmail is not null or v.verifiedAddress is not null) "
            + "and (v.verifiedAt is null or v.verifiedAt < :cutoff)")
    int clearOldDetails(@Param("cutoff") Instant cutoff);
}
