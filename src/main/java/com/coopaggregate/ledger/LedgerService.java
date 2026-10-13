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

    @Transactional
    public LedgerEntry reverse(Long entryId, String reason, Manager manager) {
        LedgerEntry original = ledgerEntryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger entry " + entryId + " does not exist."));

        if (original.getEntryType() == LedgerEntryType.REVERSAL) {
            throw new IllegalStateException("Ledger entry " + entryId + " is a reversal and cannot be reversed.");
        }
        if (ledgerEntryRepository.existsByReversesId(entryId)) {
            throw new IllegalStateException("Ledger entry " + entryId + " has already been reversed.");
        }

        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryType(LedgerEntryType.REVERSAL);
        reversal.setReverses(original);
        reversal.setRelatedEntityType(original.getRelatedEntityType());
        reversal.setRelatedEntityId(original.getRelatedEntityId());
        reversal.setQuantityKg(original.getQuantityKg() == null ? null : original.getQuantityKg().negate());
        reversal.setAmount(original.getAmount() == null ? null : -original.getAmount());
        reversal.setReason(reason);
        reversal.setManager(manager);
        return append(reversal);
    }

    @Transactional(readOnly = true)
    public VerifyResult verify() {
        String expectedPreviousHash = LedgerHasher.GENESIS_HASH;
        long checkedEntries = 0;

        for (LedgerEntry entry : ledgerEntryRepository.findAllByOrderByIdAsc()) {
            checkedEntries++;
            boolean linked = expectedPreviousHash.equals(entry.getPreviousHash());
            boolean unchanged = LedgerHasher.hash(entry).equals(entry.getCurrentHash());
            if (!linked || !unchanged) {
                return new VerifyResult(false, checkedEntries, entry.getId());
            }
            expectedPreviousHash = entry.getCurrentHash();
        }
        return new VerifyResult(true, checkedEntries, null);
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
