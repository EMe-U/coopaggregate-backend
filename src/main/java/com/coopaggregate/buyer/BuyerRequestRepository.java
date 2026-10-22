package com.coopaggregate.buyer;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface BuyerRequestRepository extends JpaRepository<BuyerRequest, Long>,
        JpaSpecificationExecutor<BuyerRequest> {

    // Loads grade and buyer in the same query, so listing a page does not run one query per row.
    @Override
    @EntityGraph(attributePaths = {"grade", "buyer"})
    Page<BuyerRequest> findAll(Specification<BuyerRequest> spec, Pageable pageable);

    // SELECT ... FOR UPDATE: two status changes or conversions of the same request wait for each other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BuyerRequest> findForUpdateById(Long id);

    boolean existsByReferenceCode(String referenceCode);

    Optional<BuyerRequest> findByReferenceCode(String referenceCode);
}
