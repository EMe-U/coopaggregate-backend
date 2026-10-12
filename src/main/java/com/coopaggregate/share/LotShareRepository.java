package com.coopaggregate.share;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LotShareRepository extends JpaRepository<LotShare, Long> {

    // Largest delivery first, so the member who gets the rounding remainder is the first one.
    @Query("""
            SELECT new com.coopaggregate.share.MemberDelivered(
                m.id, m.fullName, m.memberCode, SUM(d.quantityKg), SUM(d.deductionAmount))
            FROM Delivery d JOIN d.member m
            WHERE d.lot.id = :lotId
            GROUP BY m.id, m.fullName, m.memberCode
            ORDER BY SUM(d.quantityKg) DESC, m.id
            """)
    List<MemberDelivered> findDeliveredByMember(Long lotId);
}
