package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.coopaggregate.grade.Grade;

public interface LotRepository extends JpaRepository<Lot, Long>, JpaSpecificationExecutor<Lot> {

    Optional<Lot> findFirstByGradeAndStatus(Grade grade, LotStatus status);

    Optional<Lot> findFirstByGradeIdAndStatus(Long gradeId, LotStatus status);

    long countByLotCodeStartingWith(String prefix);

    @Query("SELECT COALESCE(SUM(d.quantityKg), 0) FROM Delivery d WHERE d.lot.id = :lotId")
    BigDecimal totalDeliveredKg(Long lotId);

    @Override
    @EntityGraph(attributePaths = "grade")
    Page<Lot> findAll(Specification<Lot> spec, Pageable pageable);

    // One query for a whole page of lots; the remaining kg is derived from these sums, never stored.
    @Query("""
            SELECT new com.coopaggregate.lot.LotQuantities(
                l.id,
                (SELECT SUM(d.quantityKg) FROM Delivery d WHERE d.lot.id = l.id),
                (SELECT SUM(x.quantityKg) FROM Loss x WHERE x.lot.id = l.id),
                (SELECT SUM(s.quantityKg) FROM Sale s WHERE s.lot.id = l.id),
                (SELECT SUM(s2.totalAmount) FROM Sale s2 WHERE s2.lot.id = l.id),
                (SELECT COUNT(DISTINCT d2.member.id) FROM Delivery d2 WHERE d2.lot.id = l.id))
            FROM Lot l
            WHERE l.id IN :lotIds
            """)
    List<LotQuantities> findQuantities(Collection<Long> lotIds);
}
