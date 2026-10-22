package com.coopaggregate.buyer;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BuyerRequestRepository extends JpaRepository<BuyerRequest, Long> {

    boolean existsByReferenceCode(String referenceCode);

    Optional<BuyerRequest> findByReferenceCode(String referenceCode);
}
