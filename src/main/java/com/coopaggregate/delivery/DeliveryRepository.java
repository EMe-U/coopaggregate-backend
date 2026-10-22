package com.coopaggregate.delivery;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface DeliveryRepository extends JpaRepository<Delivery, Long>, JpaSpecificationExecutor<Delivery> {

    boolean existsByClientUuid(UUID clientUuid);

    Optional<Delivery> findByClientUuid(UUID clientUuid);

    boolean existsByReceiptCode(String receiptCode);

    // Loads member, lot and grade in the same query, so listing a page does not run one query per row.
    @Override
    @EntityGraph(attributePaths = {"member", "lot", "lot.grade"})
    Page<Delivery> findAll(Specification<Delivery> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"member", "lot"})
    List<Delivery> findByIdIn(Collection<Long> ids);

    @Query("""
            SELECT new com.coopaggregate.delivery.GradeDeliveredKg(g.name, SUM(d.quantityKg))
            FROM Delivery d JOIN d.lot l JOIN l.grade g
            WHERE d.deliveredAt >= :from AND d.deliveredAt < :to AND d.reversedAt IS NULL
            GROUP BY g.name
            """)
    List<GradeDeliveredKg> findKgByGradeBetween(Instant from, Instant to);

    @Query("SELECT d.lot.id FROM Delivery d WHERE d.id = :id")
    Optional<Long> findLotIdById(Long id);

    List<Delivery> findByCorrectsIdIn(Collection<Long> ids);

    @EntityGraph(attributePaths = {"member"})
    List<Delivery> findByLotIdAndConfirmationStatusInAndReversedAtIsNullOrderById(
            Long lotId, Collection<ConfirmationStatus> statuses);

    Optional<Delivery> findByReceiptCode(String receiptCode);

    // SELECT ... FOR UPDATE: two code checks or sends at once cannot lose a wrong try or overwrite a code.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Delivery> findForUpdateById(Long id);

    long countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus status);

    long countByConfirmationStatusAndReversedAtIsNullAndCreatedAtBefore(ConfirmationStatus status, Instant before);
}
