package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface LotRepository extends JpaRepository<Lot, Long>, JpaSpecificationExecutor<Lot> {

    Optional<Lot> findFirstByGradeIdAndStatus(Long gradeId, LotStatus status);

    long countByLotCodeStartingWith(String prefix);

    long countByStatus(LotStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Lot> findForUpdateById(Long id);

    // SELECT ... FOR UPDATE on the grade's lot being filled; there is at most one (unique index).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Lot l WHERE l.grade.id = :gradeId AND l.status = com.coopaggregate.lot.LotStatus.FILLING")
    Optional<Lot> findFillingForUpdate(Long gradeId);

    // Once a fixed-size lot exists, the lot size can no longer change.
    boolean existsByCapacityKgIsNotNull();

    @Query("SELECT l.grade.id FROM Lot l WHERE l.id = :id")
    Optional<Long> findGradeIdById(Long id);

    // Reversed deliveries and payments stay in their tables for the history but are never counted.
    @Query("SELECT COALESCE(SUM(d.quantityKg), 0) FROM Delivery d WHERE d.lot.id = :lotId AND d.reversedAt IS NULL")
    BigDecimal totalDeliveredKg(Long lotId);

    @EntityGraph(attributePaths = "grade")
    List<Lot> findByStatusIn(Collection<LotStatus> statuses);

    @Override
    @EntityGraph(attributePaths = "grade")
    Page<Lot> findAll(Specification<Lot> spec, Pageable pageable);

    // One query for a whole page of lots, summed from the lot's portions of deliveries that are not reversed; the
    // remaining kg is derived from these sums, never stored. The sale amount is the total of the sales made from this
    // lot before sales by grade, plus what sales by grade credited for its portions (allocations from before
    // fixed-size lots have no portion and are found through their delivery's own lot).
    @Query("""
            SELECT new com.coopaggregate.lot.LotQuantities(
                l.id,
                (SELECT SUM(p.quantityKg) FROM LotPortion p WHERE p.lot.id = l.id AND p.delivery.reversedAt IS NULL),
                (SELECT SUM(p2.lostKg) FROM LotPortion p2 WHERE p2.lot.id = l.id AND p2.delivery.reversedAt IS NULL),
                (SELECT SUM(p3.soldKg) FROM LotPortion p3 WHERE p3.lot.id = l.id AND p3.delivery.reversedAt IS NULL),
                (COALESCE((SELECT SUM(s2.totalAmount) FROM Sale s2 WHERE s2.lot.id = l.id), 0)
                    + COALESCE((SELECT SUM(a.grossRwf) FROM SaleAllocation a WHERE a.lotPortion.lot.id = l.id), 0)
                    + COALESCE((SELECT SUM(a2.grossRwf) FROM SaleAllocation a2
                                WHERE a2.lotPortion IS NULL AND a2.delivery.lot.id = l.id), 0)),
                (SELECT COUNT(DISTINCT p4.delivery.member.id) FROM LotPortion p4
                    WHERE p4.lot.id = l.id AND p4.delivery.reversedAt IS NULL),
                (SELECT SUM(p5.quantityKg) FROM LotPortion p5
                    WHERE p5.lot.id = l.id AND p5.delivery.reversedAt IS NULL
                    AND p5.delivery.confirmationStatus = com.coopaggregate.delivery.ConfirmationStatus.PENDING))
            FROM Lot l
            WHERE l.id IN :lotIds
            """)
    List<LotQuantities> findQuantities(Collection<Long> lotIds);

    // Kg left per grade, read from the batches (deliveries) whether or not they are in a lot yet, split by confirmation
    // status. Reversed deliveries are not batches. Only confirmed kg can be sold.
    @Query("""
            SELECT new com.coopaggregate.lot.GradeKgLeft(g.code, g.name, d.confirmationStatus, SUM(d.remainingKg))
            FROM Delivery d JOIN d.grade g
            WHERE d.reversedAt IS NULL AND d.remainingKg > 0
            GROUP BY g.code, g.name, d.confirmationStatus
            """)
    List<GradeKgLeft> findKgLeftByGradeAndStatus();

    // Each member's kg in the lot, and how much of it was sold, lost and is left, from their portions of deliveries
    // that are not reversed.
    @Query("""
            SELECT new com.coopaggregate.lot.LotMemberResponse(m.id, m.fullName, m.memberCode, SUM(p.quantityKg),
                SUM(p.soldKg), SUM(p.lostKg), SUM(p.quantityKg - p.soldKg - p.lostKg))
            FROM LotPortion p JOIN p.delivery d JOIN d.member m
            WHERE p.lot.id = :lotId AND d.reversedAt IS NULL
            GROUP BY m.id, m.fullName, m.memberCode
            ORDER BY SUM(p.quantityKg) DESC, m.fullName
            """)
    List<LotMemberResponse> findMembers(Long lotId);

    @Query("SELECT COALESCE(SUM(d.quantityKg), 0) FROM Delivery d WHERE d.reversedAt IS NULL")
    BigDecimal totalDeliveredKg();

    @Query("SELECT COALESCE(SUM(d.quantityKg), 0) FROM Delivery d WHERE d.reversedAt IS NULL "
            + "AND d.confirmationStatus = com.coopaggregate.delivery.ConfirmationStatus.PENDING")
    BigDecimal totalPendingConfirmationKg();

    @Query("SELECT COALESCE(SUM(x.quantityKg), 0) FROM Loss x")
    BigDecimal totalLostKg();

    @Query("SELECT COALESCE(SUM(s.quantityKg), 0) FROM Sale s")
    BigDecimal totalSoldKg();

    @Query("SELECT COALESCE(SUM(d.quantityKg), 0) FROM Delivery d "
            + "WHERE d.deliveredAt >= :from AND d.deliveredAt < :to AND d.reversedAt IS NULL")
    BigDecimal deliveredKgBetween(Instant from, Instant to);

    @Query("SELECT COUNT(d) FROM Delivery d "
            + "WHERE d.deliveredAt >= :from AND d.deliveredAt < :to AND d.reversedAt IS NULL")
    long deliveryCountBetween(Instant from, Instant to);
}
