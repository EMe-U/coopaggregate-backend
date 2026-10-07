package com.coopaggregate.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.manager.Manager;

class LedgerHasherTest {

    private static final Instant ENTRY_DATE = Instant.parse("2026-10-07T08:30:15.123456Z");

    @Test
    void sameInputGivesSameHash() {
        String hash = LedgerHasher.hash(sampleEntry());

        assertEquals(hash, LedgerHasher.hash(sampleEntry()));
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void changingAnyFieldChangesHash() {
        Map<String, Consumer<LedgerEntry>> changes = new LinkedHashMap<>();
        changes.put("previousHash", e -> e.setPreviousHash("1".repeat(64)));
        changes.put("entryDate", e -> e.setEntryDate(ENTRY_DATE.plusSeconds(1)));
        changes.put("entryType", e -> e.setEntryType(LedgerEntryType.SALE));
        changes.put("relatedEntityType", e -> e.setRelatedEntityType("LOSS"));
        changes.put("relatedEntityId", e -> e.setRelatedEntityId(43L));
        changes.put("quantityKg", e -> e.setQuantityKg(new BigDecimal("120.51")));
        changes.put("amount", e -> e.setAmount(601L));
        changes.put("reason", e -> e.setReason("Corrected weight"));
        changes.put("reverses", e -> e.setReverses(entryWithId(7L)));
        changes.put("manager", e -> e.setManager(managerWithId(2L)));

        String original = LedgerHasher.hash(sampleEntry());

        changes.forEach((field, change) -> {
            LedgerEntry entry = sampleEntry();
            change.accept(entry);
            assertNotEquals(original, LedgerHasher.hash(entry), "Changing " + field + " must change the hash");
        });
    }

    @Test
    void hashIsStableAfterDatabaseRoundTrip() {
        LedgerEntry beforeSave = sampleEntry();
        beforeSave.setEntryDate(ENTRY_DATE.plusNanos(789));
        beforeSave.setQuantityKg(new BigDecimal("120.5"));

        LedgerEntry readBack = sampleEntry();
        readBack.setQuantityKg(new BigDecimal("120.50"));

        assertEquals(LedgerHasher.hash(beforeSave), LedgerHasher.hash(readBack));
    }

    @Test
    void changingMiddleEntryBreaksChain() {
        List<LedgerEntry> chain = buildChain(3);
        assertTrue(isChainValid(chain));

        chain.get(1).setQuantityKg(new BigDecimal("999.00"));
        assertFalse(isChainValid(chain));

        // Recomputing the edited entry's hash still breaks the link to the next entry.
        chain.get(1).setCurrentHash(LedgerHasher.hash(chain.get(1)));
        assertFalse(isChainValid(chain));
    }

    private static List<LedgerEntry> buildChain(int size) {
        List<LedgerEntry> chain = new ArrayList<>();
        String previousHash = LedgerHasher.GENESIS_HASH;
        for (int i = 0; i < size; i++) {
            LedgerEntry entry = sampleEntry();
            entry.setPreviousHash(previousHash);
            entry.setRelatedEntityId((long) i + 1);
            entry.setCurrentHash(LedgerHasher.hash(entry));
            previousHash = entry.getCurrentHash();
            chain.add(entry);
        }
        return chain;
    }

    private static boolean isChainValid(List<LedgerEntry> chain) {
        String expectedPrevious = LedgerHasher.GENESIS_HASH;
        for (LedgerEntry entry : chain) {
            if (!expectedPrevious.equals(entry.getPreviousHash())
                    || !LedgerHasher.hash(entry).equals(entry.getCurrentHash())) {
                return false;
            }
            expectedPrevious = entry.getCurrentHash();
        }
        return true;
    }

    private static LedgerEntry sampleEntry() {
        LedgerEntry entry = new LedgerEntry();
        entry.setPreviousHash(LedgerHasher.GENESIS_HASH);
        entry.setEntryDate(ENTRY_DATE);
        entry.setEntryType(LedgerEntryType.DELIVERY);
        entry.setRelatedEntityType("DELIVERY");
        entry.setRelatedEntityId(42L);
        entry.setQuantityKg(new BigDecimal("120.50"));
        entry.setAmount(600L);
        entry.setReason("Morning delivery");
        entry.setManager(managerWithId(1L));
        return entry;
    }

    private static Manager managerWithId(Long id) {
        Manager manager = new Manager();
        ReflectionTestUtils.setField(manager, "id", id);
        return manager;
    }

    private static LedgerEntry entryWithId(Long id) {
        LedgerEntry entry = new LedgerEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        return entry;
    }
}
