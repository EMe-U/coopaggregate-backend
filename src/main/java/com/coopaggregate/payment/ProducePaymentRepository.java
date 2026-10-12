package com.coopaggregate.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProducePaymentRepository extends JpaRepository<ProducePayment, Long> {

    // Share minus deduction per member. Only shared lots have lot_share rows.
    @Query("""
            SELECT new com.coopaggregate.payment.MemberAmount(ls.member.id, SUM(ls.shareAmount - ls.deductionAmount))
            FROM LotShare ls
            GROUP BY ls.member.id
            """)
    List<MemberAmount> findNetSharesByMember();

    @Query("""
            SELECT new com.coopaggregate.payment.MemberAmount(p.member.id, SUM(p.amount))
            FROM ProducePayment p
            GROUP BY p.member.id
            """)
    List<MemberAmount> findPaymentsByMember();

    Optional<ProducePayment> findByClientUuid(UUID clientUuid);

    @Query("SELECT COALESCE(SUM(ls.shareAmount - ls.deductionAmount), 0) FROM LotShare ls "
            + "WHERE ls.member.id = :memberId")
    long netSharesOf(Long memberId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM ProducePayment p WHERE p.member.id = :memberId")
    long paidTo(Long memberId);
}
