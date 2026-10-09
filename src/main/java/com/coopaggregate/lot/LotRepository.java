package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.coopaggregate.grade.Grade;

public interface LotRepository extends JpaRepository<Lot, Long> {

    Optional<Lot> findFirstByGradeAndStatus(Grade grade, LotStatus status);

    Optional<Lot> findFirstByGradeIdAndStatus(Long gradeId, LotStatus status);

    long countByLotCodeStartingWith(String prefix);

    @Query("SELECT COALESCE(SUM(d.quantityKg), 0) FROM Delivery d WHERE d.lot.id = :lotId")
    BigDecimal totalDeliveredKg(Long lotId);
}
