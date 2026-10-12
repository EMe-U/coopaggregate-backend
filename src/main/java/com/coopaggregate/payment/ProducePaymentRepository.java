package com.coopaggregate.payment;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ProducePaymentRepository extends JpaRepository<ProducePayment, Long>,
        JpaSpecificationExecutor<ProducePayment> {

    @Override
    @EntityGraph(attributePaths = "member")
    Page<ProducePayment> findAll(Specification<ProducePayment> spec, Pageable pageable);

    // Only shared lots have lot_share rows, so these are the members with at least one shared lot.
    @Query("""
            SELECT new com.coopaggregate.payment.MemberShareTotals(
                ls.member.id, SUM(ls.shareAmount), SUM(ls.deductionAmount))
            FROM LotShare ls
            GROUP BY ls.member.id
            """)
    List<MemberShareTotals> findShareTotalsByMember();

    @Query("""
            SELECT new com.coopaggregate.payment.MemberAmount(p.member.id, SUM(p.amount))
            FROM ProducePayment p
            GROUP BY p.member.id
            """)
    List<MemberAmount> findPaymentsByMember();

    Optional<ProducePayment> findByClientUuid(UUID clientUuid);

    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(payment_code FROM '[0-9]+$') AS INTEGER)), 0) "
            + "FROM produce_payment", nativeQuery = true)
    int highestPaymentNumber();

    List<ProducePayment> findByMemberIdOrderByPaymentDateDescIdDesc(Long memberId);

    @Query("SELECT COALESCE(SUM(ls.shareAmount - ls.deductionAmount), 0) FROM LotShare ls "
            + "WHERE ls.member.id = :memberId")
    long netSharesOf(Long memberId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM ProducePayment p WHERE p.member.id = :memberId")
    long paidTo(Long memberId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM ProducePayment p WHERE p.paymentDate BETWEEN :from AND :to")
    long paidBetween(LocalDate from, LocalDate to);
}
