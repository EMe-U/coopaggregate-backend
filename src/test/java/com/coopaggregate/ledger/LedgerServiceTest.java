package com.coopaggregate.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.manager.Manager;

class LedgerServiceTest {

    private LedgerEntryRepository repository;
    private LedgerService service;
    private Manager manager;

    @BeforeEach
    void setUp() {
        repository = mock(LedgerEntryRepository.class);
        service = new LedgerService(repository);
        manager = new Manager();
        ReflectionTestUtils.setField(manager, "id", 1L);
        when(repository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void firstEntryUsesGenesisHash() {
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());

        LedgerEntry entry = recordDelivery();

        assertEquals(LedgerHasher.GENESIS_HASH, entry.getPreviousHash());
        assertEquals(LedgerHasher.hash(entry), entry.getCurrentHash());
    }

    @Test
    void entryLinksToLatestEntryHash() {
        LedgerEntry latest = new LedgerEntry();
        latest.setCurrentHash("a".repeat(64));
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(latest));

        LedgerEntry entry = recordDelivery();

        assertEquals("a".repeat(64), entry.getPreviousHash());
        assertEquals(LedgerHasher.hash(entry), entry.getCurrentHash());
    }

    @Test
    void recordSetsEntryDateInMicroseconds() {
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());

        LedgerEntry entry = recordDelivery();

        assertEquals(entry.getEntryDate().truncatedTo(ChronoUnit.MICROS), entry.getEntryDate());
    }

    @Test
    void lockIsTakenBeforeReadingLatestEntry() {
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());

        recordDelivery();

        InOrder order = inOrder(repository);
        order.verify(repository).lockForAppend(anyLong());
        order.verify(repository).findTopByOrderByIdDesc();
        order.verify(repository).save(any(LedgerEntry.class));
    }

    @Test
    void reverseCreatesNegatedReversal() {
        LedgerEntry original = storedEntry(5L, LedgerEntryType.DELIVERY);
        when(repository.findById(5L)).thenReturn(Optional.of(original));
        when(repository.existsByReversesId(5L)).thenReturn(false);
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(original));

        LedgerEntry reversal = service.reverse(5L, "Wrong member", manager);

        assertEquals(LedgerEntryType.REVERSAL, reversal.getEntryType());
        assertSame(original, reversal.getReverses());
        assertEquals(new BigDecimal("-120.50"), reversal.getQuantityKg());
        assertEquals(-600L, reversal.getAmount());
        assertEquals("DELIVERY", reversal.getRelatedEntityType());
        assertEquals(42L, reversal.getRelatedEntityId());
        assertEquals("Wrong member", reversal.getReason());
        assertEquals(original.getCurrentHash(), reversal.getPreviousHash());
    }

    @Test
    void reversalCannotBeReversed() {
        when(repository.findById(5L)).thenReturn(Optional.of(storedEntry(5L, LedgerEntryType.REVERSAL)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reverse(5L, "Undo", manager));

        assertEquals("Ledger entry 5 is a reversal and cannot be reversed.", error.getMessage());
        verify(repository, never()).save(any(LedgerEntry.class));
    }

    @Test
    void entryCanOnlyBeReversedOnce() {
        when(repository.findById(5L)).thenReturn(Optional.of(storedEntry(5L, LedgerEntryType.DELIVERY)));
        when(repository.existsByReversesId(5L)).thenReturn(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reverse(5L, "Again", manager));

        assertEquals("Ledger entry 5 has already been reversed.", error.getMessage());
        verify(repository, never()).save(any(LedgerEntry.class));
    }

    @Test
    void reversingUnknownEntryFails() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.reverse(99L, "Undo", manager));

        assertEquals("Ledger entry 99 does not exist.", error.getMessage());
    }

    @Test
    void verifyAcceptsValidChain() {
        List<LedgerEntry> chain = buildChain(3);
        when(repository.findAllByOrderByIdAsc()).thenReturn(chain);

        VerifyResult result = service.verify();

        assertTrue(result.valid());
        assertEquals(3, result.checkedEntries());
        assertNull(result.firstBrokenEntryId());
    }

    @Test
    void verifyAcceptsEmptyLedger() {
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of());

        VerifyResult result = service.verify();

        assertTrue(result.valid());
        assertEquals(0, result.checkedEntries());
    }

    @Test
    void verifyFindsEditedEntry() {
        List<LedgerEntry> chain = buildChain(3);
        chain.get(1).setAmount(1_000_000L);
        when(repository.findAllByOrderByIdAsc()).thenReturn(chain);

        VerifyResult result = service.verify();

        assertFalse(result.valid());
        assertEquals(2, result.checkedEntries());
        assertEquals(2L, result.firstBrokenEntryId());
    }

    @Test
    void verifyFindsBrokenLinkWhenHashWasRecomputed() {
        List<LedgerEntry> chain = buildChain(3);
        chain.get(1).setAmount(1_000_000L);
        chain.get(1).setCurrentHash(LedgerHasher.hash(chain.get(1)));
        when(repository.findAllByOrderByIdAsc()).thenReturn(chain);

        VerifyResult result = service.verify();

        assertFalse(result.valid());
        assertEquals(3L, result.firstBrokenEntryId());
    }

    private LedgerEntry recordDelivery() {
        return service.record(LedgerEntryType.DELIVERY, "DELIVERY", 42L,
                new BigDecimal("120.50"), 600L, null, manager);
    }

    private LedgerEntry storedEntry(Long id, LedgerEntryType type) {
        LedgerEntry entry = new LedgerEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        entry.setEntryType(type);
        entry.setRelatedEntityType("DELIVERY");
        entry.setRelatedEntityId(42L);
        entry.setQuantityKg(new BigDecimal("120.50"));
        entry.setAmount(600L);
        entry.setManager(manager);
        entry.setCurrentHash("b".repeat(64));
        return entry;
    }

    private List<LedgerEntry> buildChain(int size) {
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());
        List<LedgerEntry> chain = new ArrayList<>();
        for (long id = 1; id <= size; id++) {
            LedgerEntry entry = recordDelivery();
            ReflectionTestUtils.setField(entry, "id", id);
            chain.add(entry);
            when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(entry));
        }
        return chain;
    }
}
