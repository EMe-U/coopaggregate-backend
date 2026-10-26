package com.coopaggregate.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.sale.Sale;

/**
 * The weekly report's own totals, added up in the database. Where another page already shows a number, the report
 * uses that page's query instead (see ReportService), so the two always match. Reversed deliveries and payments are
 * left out; sales, losses and shares cannot be reversed.
 */
public interface ReportRepository extends Repository<Delivery, Long> {

    @Query("""
            SELECT new com.coopaggregate.report.GradeCount(g.name, COUNT(d))
            FROM Delivery d JOIN d.lot l JOIN l.grade g
            WHERE d.deliveredAt >= :from AND d.deliveredAt < :to AND d.reversedAt IS NULL
            GROUP BY g.name
            """)
    List<GradeCount> countDeliveriesByGradeBetween(Instant from, Instant to);

    @Query("""
            SELECT new com.coopaggregate.report.MemberGradeKg(m.id, m.memberCode, m.fullName, g.name, SUM(d.quantityKg))
            FROM Delivery d JOIN d.member m JOIN d.lot l JOIN l.grade g
            WHERE d.deliveredAt >= :from AND d.deliveredAt < :to AND d.reversedAt IS NULL
            GROUP BY m.id, m.memberCode, m.fullName, g.name
            """)
    List<MemberGradeKg> findMemberGradeKgBetween(Instant from, Instant to);

    @Query("""
            SELECT new com.coopaggregate.report.ReasonKg(x.reason, SUM(x.quantityKg))
            FROM Loss x
            WHERE x.lossDate BETWEEN :from AND :to
            GROUP BY x.reason
            """)
    List<ReasonKg> findLossKgByReasonBetween(LocalDate from, LocalDate to);

    // Only the week's sales are loaded, with their lot, buyer and order, to list them one per sale or order.
    @Query("""
            SELECT s FROM Sale s JOIN FETCH s.lot JOIN FETCH s.buyer LEFT JOIN FETCH s.saleOrder
            WHERE s.saleDate BETWEEN :from AND :to
            ORDER BY s.saleDate, s.id
            """)
    List<Sale> findSalesBetween(LocalDate from, LocalDate to);

    // Shares have no date of their own, so the moment the manager shared the lot is used.
    @Query("SELECT COALESCE(SUM(ls.shareAmount), 0) FROM LotShare ls WHERE ls.createdAt >= :from AND ls.createdAt < :to")
    long sumSharedBetween(Instant from, Instant to);

    @Query("SELECT COALESCE(SUM(ls.deductionAmount), 0) FROM LotShare ls "
            + "WHERE ls.createdAt >= :from AND ls.createdAt < :to")
    long sumShareDeductionsBetween(Instant from, Instant to);

    @Query("""
            SELECT new com.coopaggregate.report.MethodAmount(p.method, SUM(p.amount))
            FROM ProducePayment p
            WHERE p.paymentDate BETWEEN :from AND :to AND p.reversedAt IS NULL
            GROUP BY p.method
            """)
    List<MethodAmount> findPaidByMethodBetween(LocalDate from, LocalDate to);

    // Confirmed in the week, whenever the delivery itself was made.
    @Query("""
            SELECT new com.coopaggregate.report.MethodCount(d.confirmationMethod, COUNT(d))
            FROM Delivery d
            WHERE d.confirmationStatus = com.coopaggregate.delivery.ConfirmationStatus.CONFIRMED
              AND d.confirmedAt >= :from AND d.confirmedAt < :to AND d.reversedAt IS NULL
            GROUP BY d.confirmationMethod
            """)
    List<MethodCount> findConfirmedByMethodBetween(Instant from, Instant to);

    @Query("""
            SELECT COUNT(d) FROM Delivery d
            WHERE d.confirmationStatus = com.coopaggregate.delivery.ConfirmationStatus.PENDING
              AND d.deliveredAt >= :from AND d.deliveredAt < :to AND d.reversedAt IS NULL
            """)
    long countPendingDeliveredBetween(Instant from, Instant to);

    // Settled the same way as on the disputes page: found correct (confirmed) or reversed, at that moment.
    @Query("""
            SELECT COUNT(d) FROM Delivery d
            WHERE d.disputedAt IS NOT NULL
              AND ((d.reversedAt IS NULL
                    AND d.confirmationStatus <> com.coopaggregate.delivery.ConfirmationStatus.DISPUTED
                    AND d.confirmedAt >= :from AND d.confirmedAt < :to)
                   OR (d.reversedAt >= :from AND d.reversedAt < :to))
            """)
    long countDisputesSettledBetween(Instant from, Instant to);
}
