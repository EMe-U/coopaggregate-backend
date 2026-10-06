package com.coopaggregate.ledger;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Optional<LedgerEntry> findTopByOrderByIdDesc();
}
