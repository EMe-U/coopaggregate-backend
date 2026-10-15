package com.coopaggregate.share;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LotShareRepository extends JpaRepository<LotShare, Long> {

    // Largest delivery first, so the member who gets the rounding remainder is the first one.
    @Query("""
            SELECT new com.coopaggregate.share.MemberDelivered(
                m.id, m.fullName, m.memberCode, SUM(d.quantityKg), SUM(d.deductionAmount))
            FROM Delivery d JOIN d.member m
            WHERE d.lot.id = :lotId AND d.reversedAt IS NULL
            GROUP BY m.id, m.fullName, m.memberCode
            ORDER BY SUM(d.quantityKg) DESC, m.id
            """)
    List<MemberDelivered> findDeliveredByMember(Long lotId);

    List<LotShare> findByMemberId(Long memberId);

    @Query("""
            SELECT new com.coopaggregate.share.MemberLotKg(l.id, l.lotCode, l.status, SUM(d.quantityKg))
            FROM Delivery d JOIN d.lot l
            WHERE d.member.id = :memberId AND d.reversedAt IS NULL
            GROUP BY l.id, l.lotCode, l.status
            ORDER BY l.id DESC
            """)
    List<MemberLotKg> findLotsOfMember(Long memberId);

    @EntityGraph(attributePaths = {"member", "lot"})
    List<LotShare> findByIdIn(Collection<Long> ids);
}
