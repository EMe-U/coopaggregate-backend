package com.coopaggregate.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.manager.Manager;

@Service
public class LedgerService {

    private static final long LEDGER_LOCK_KEY = 20261007L;

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    // Runs in the caller's transaction so the business record and its ledger entry are saved or rolled back together.
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry record(LedgerEntryType entryType, String relatedEntityType, Long relatedEntityId,
                              BigDecimal quantityKg, Long amount, String reason, Manager manager) {
        LedgerEntry entry = new LedgerEntry();
        entry.setEntryType(entryType);
        entry.setRelatedEntityType(relatedEntityType);
        entry.setRelatedEntityId(relatedEntityId);
        entry.setQuantityKg(quantityKg);
        entry.setAmount(amount);
        entry.setReason(reason);
        entry.setManager(manager);
        return append(entry);
    }

    private LedgerEntry append(LedgerEntry entry) {
        // Without the lock, two transactions could read the same latest entry and both
        // use its hash as previous_hash, which would fork the chain.
        ledgerEntryRepository.lockForAppend(LEDGER_LOCK_KEY);

        String previousHash = ledgerEntryRepository.findTopByOrderByIdDesc()
                .map(LedgerEntry::getCurrentHash)
                .orElse(LedgerHasher.GENESIS_HASH);

        entry.setPreviousHash(previousHash);
        entry.setEntryDate(Instant.now().truncatedTo(ChronoUnit.MICROS));
        entry.setCurrentHash(LedgerHasher.hash(entry));
        return ledgerEntryRepository.save(entry);
    }
}
