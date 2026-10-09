package com.coopaggregate.delivery;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeliveryRepository extends JpaRepository<Delivery, Long>, JpaSpecificationExecutor<Delivery> {

    boolean existsByClientUuid(UUID clientUuid);

    Optional<Delivery> findByClientUuid(UUID clientUuid);

    boolean existsByReceiptCode(String receiptCode);

    // Loads member, lot and grade in the same query, so listing a page does not run one query per row.
    @Override
    @EntityGraph(attributePaths = {"member", "lot", "lot.grade"})
    Page<Delivery> findAll(Specification<Delivery> spec, Pageable pageable);
}
