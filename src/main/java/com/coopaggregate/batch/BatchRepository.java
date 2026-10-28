package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;

import jakarta.persistence.LockModeType;

/** Deliveries seen as batches. Reversed deliveries are not batches and are always left out. */
public interface BatchRepository extends Repository<Delivery, Long> {

    // SELECT ... FOR UPDATE, oldest first. Callers already hold the lot's lock, so the order of locks stays
    // grade, lot, then batches.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT d FROM Delivery d
            WHERE d.lot.id = :lotId AND d.reversedAt IS NULL AND d.remainingKg > 0
            ORDER BY d.deliveredAt, d.id
            """)
    List<Delivery> lockBatchesWithKgLeft(Long lotId);

    @EntityGraph(attributePaths = {"member", "lot", "lot.grade"})
    @Query(value = """
            SELECT d FROM Delivery d
            WHERE d.member.id = :memberId AND d.reversedAt IS NULL AND (:unsoldOnly = false OR d.remainingKg > 0)
            ORDER BY d.deliveredAt DESC, d.id DESC
            """, countQuery = """
            SELECT COUNT(d) FROM Delivery d
            WHERE d.member.id = :memberId AND d.reversedAt IS NULL AND (:unsoldOnly = false OR d.remainingKg > 0)
            """)
    Page<Delivery> findMemberBatches(Long memberId, boolean unsoldOnly, Pageable pageable);

    @Query("""
            SELECT new com.coopaggregate.batch.BatchTotals(COALESCE(SUM(d.quantityKg), 0), COALESCE(SUM(d.soldKg), 0),
                COALESCE(SUM(d.lostKg), 0), COALESCE(SUM(d.remainingKg), 0))
            FROM Delivery d
            WHERE d.member.id = :memberId AND d.reversedAt IS NULL
            """)
    BatchTotals memberTotals(Long memberId);

    // FIFO order: the order sales and losses take kg in.
    @EntityGraph(attributePaths = {"member", "lot", "lot.grade"})
    @Query(value = """
            SELECT d FROM Delivery d
            WHERE d.lot.grade.id = :gradeId AND d.reversedAt IS NULL AND d.remainingKg > 0
            ORDER BY d.deliveredAt, d.id
            """, countQuery = """
            SELECT COUNT(d) FROM Delivery d
            WHERE d.lot.grade.id = :gradeId AND d.reversedAt IS NULL AND d.remainingKg > 0
            """)
    Page<Delivery> findGradeBatchesWithKgLeft(Long gradeId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(d.remainingKg), 0) FROM Delivery d WHERE d.lot.grade.id = :gradeId AND d.reversedAt IS NULL")
    BigDecimal gradeRemainingKg(Long gradeId);

    // The batches a sale of the grade may use, in the order it uses them: confirmed (by the member, by the manager or
    // recorded before confirmations existed), not reversed and with kg left, whatever their lot. Not locked.
    @EntityGraph(attributePaths = {"member", "lot"})
    @Query("""
            SELECT d FROM Delivery d
            WHERE d.lot.grade.id = :gradeId AND d.reversedAt IS NULL AND d.remainingKg > 0
              AND d.confirmationStatus = com.coopaggregate.delivery.ConfirmationStatus.CONFIRMED
            ORDER BY d.deliveredAt, d.id
            """)
    List<Delivery> findSellableBatches(Long gradeId);

    @Query("""
            SELECT COALESCE(SUM(d.remainingKg), 0) FROM Delivery d
            WHERE d.lot.grade.id = :gradeId AND d.reversedAt IS NULL AND d.confirmationStatus = :status
            """)
    BigDecimal gradeRemainingKgWithStatus(Long gradeId, ConfirmationStatus status);
}
