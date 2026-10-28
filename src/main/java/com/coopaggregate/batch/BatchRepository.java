package com.coopaggregate.batch;

import java.util.List;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.coopaggregate.delivery.Delivery;

import jakarta.persistence.LockModeType;

/** Deliveries seen as batches. Reversed deliveries are not batches and are always left out. */
public interface BatchRepository extends Repository<Delivery, Long> {

    // SELECT ... FOR UPDATE, oldest first. Callers already hold the lot's lock, so the order of locks stays
    // grade, lot, then batches.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT d FROM Delivery d
            WHERE d.lot.id = :lotId AND d.reversedAt IS NULL AND d.remainingKg > 0
            ORDER BY d.deliveredAt, d.id
            """)
    List<Delivery> lockBatchesWithKgLeft(Long lotId);
}
