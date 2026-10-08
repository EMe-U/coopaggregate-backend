package com.coopaggregate.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Optional<LedgerEntry> findTopByOrderByIdDesc();

    boolean existsByReversesId(Long entryId);

    List<LedgerEntry> findAllByOrderByIdAsc();

    List<LedgerEntry> findByReversesIdIn(Collection<Long> entryIds);

    // Held until the transaction ends. Selecting 1 avoids mapping Postgres's void return type.
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
    Integer lockForAppend(long key);
}
