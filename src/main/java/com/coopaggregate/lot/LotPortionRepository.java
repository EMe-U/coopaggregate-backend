package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface LotPortionRepository extends JpaRepository<LotPortion, Long> {

    boolean existsByDeliveryId(Long deliveryId);

    List<LotPortion> findByDeliveryIdOrderById(Long deliveryId);

    Optional<LotPortion> findByLotIdAndDeliveryId(Long lotId, Long deliveryId);

    // The portions a sale of the grade may take kg from: of confirmed, not reversed deliveries, with kg left, in any lot
    // (older lots too). Not locked: the caller holds the grade's lock, under which none of this can change.
    @Query("""
            SELECT p FROM LotPortion p JOIN FETCH p.lot l JOIN FETCH p.delivery d JOIN FETCH d.member
            WHERE l.grade.id = :gradeId AND p.remainingKg > 0 AND d.reversedAt IS NULL
              AND d.confirmationStatus = com.coopaggregate.delivery.ConfirmationStatus.CONFIRMED
            """)
    List<LotPortion> findSellable(Long gradeId);

    // The lots a sale of the grade may take kg from, in id order: the order the sale locks them in.
    @Query("""
            SELECT DISTINCT p.lot.id FROM LotPortion p
            WHERE p.lot.grade.id = :gradeId AND p.remainingKg > 0 AND p.delivery.reversedAt IS NULL
              AND p.delivery.confirmationStatus = com.coopaggregate.delivery.ConfirmationStatus.CONFIRMED
            ORDER BY p.lot.id
            """)
    List<Long> findLotIdsWithSellable(Long gradeId);

    // SELECT ... FOR UPDATE, in id order. Callers hold the grade and the lots first.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM LotPortion p WHERE p.id IN :ids ORDER BY p.id")
    List<LotPortion> lockByIds(Collection<Long> ids);

    // What a lot still holds: the kg of its portions not sold or lost, of deliveries that are not reversed (pending ones
    // included, in the lots from before fixed-size lots).
    @Query("""
            SELECT COALESCE(SUM(p.quantityKg - p.soldKg - p.lostKg), 0) FROM LotPortion p
            WHERE p.lot.id = :lotId AND p.delivery.reversedAt IS NULL
            """)
    BigDecimal remainingKg(Long lotId);

    // The kg already in a lot: every portion it was given, also of deliveries reversed since (their kg leave a
    // shortfall; a lot is never refilled).
    @Query("SELECT COALESCE(SUM(p.quantityKg), 0) FROM LotPortion p WHERE p.lot.id = :lotId")
    BigDecimal filledKg(Long lotId);
}
