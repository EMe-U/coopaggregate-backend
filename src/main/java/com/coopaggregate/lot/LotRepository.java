package com.coopaggregate.lot;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coopaggregate.grade.Grade;

public interface LotRepository extends JpaRepository<Lot, Long> {

    Optional<Lot> findFirstByGradeAndStatus(Grade grade, LotStatus status);

    long countByLotCodeStartingWith(String prefix);
}
