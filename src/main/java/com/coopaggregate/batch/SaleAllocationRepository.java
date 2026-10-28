package com.coopaggregate.batch;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Credits are allocations with money: what a sale pays a member for one batch. Allocations of sales made before
 * sales by grade have no money (gross is null); their members are paid through lot sharing, so they are never
 * counted here. Credits are not filtered on reversed deliveries: a batch that has sold kg cannot be reversed, and
 * money already credited stays owed.
 */
public interface SaleAllocationRepository extends JpaRepository<SaleAllocation, Long> {

    List<SaleAllocation> findBySaleIdOrderById(Long saleId);

    @Query("""
            SELECT new com.coopaggregate.batch.MemberCredit(a.delivery.member.id, SUM(a.grossRwf), SUM(a.deductionRwf))
            FROM SaleAllocation a
            WHERE a.grossRwf IS NOT NULL
            GROUP BY a.delivery.member.id
            """)
    List<MemberCredit> findCreditTotalsByMember();

    @Query("""
            SELECT COALESCE(SUM(a.grossRwf - a.deductionRwf), 0) FROM SaleAllocation a
            WHERE a.grossRwf IS NOT NULL AND a.delivery.member.id = :memberId
            """)
    long netCreditsOf(Long memberId);

    @Query("""
            SELECT new com.coopaggregate.batch.BatchEarnings(COALESCE(SUM(a.grossRwf), 0),
                COALESCE(SUM(a.deductionRwf), 0))
            FROM SaleAllocation a
            WHERE a.grossRwf IS NOT NULL AND a.delivery.member.id = :memberId
            """)
    BatchEarnings memberEarnings(Long memberId);

    @Query("""
            SELECT new com.coopaggregate.batch.BatchCredit(a.delivery.id, SUM(a.quantityKg), SUM(a.grossRwf),
                SUM(a.deductionRwf))
            FROM SaleAllocation a
            WHERE a.grossRwf IS NOT NULL AND a.delivery.id IN :deliveryIds
            GROUP BY a.delivery.id
            """)
    List<BatchCredit> findCreditsOfBatches(Collection<Long> deliveryIds);

    // Newest sale first, for the member's statement.
    @EntityGraph(attributePaths = {"sale", "delivery", "delivery.lot"})
    @Query("""
            SELECT a FROM SaleAllocation a
            WHERE a.grossRwf IS NOT NULL AND a.delivery.member.id = :memberId
            ORDER BY a.sale.saleDate DESC, a.id DESC
            """)
    List<SaleAllocation> findCreditsOfMember(Long memberId);
}
