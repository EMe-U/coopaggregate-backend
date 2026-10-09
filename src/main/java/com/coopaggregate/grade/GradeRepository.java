package com.coopaggregate.grade;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface GradeRepository extends JpaRepository<Grade, Long> {

    List<Grade> findByActiveTrueOrderByCodeAsc();

    // SELECT ... FOR UPDATE: deliveries of the same grade wait for each other until the transaction ends.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Grade> findForUpdateById(Long id);
}
