package com.coopaggregate.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.manager.Manager;

/** Reversals only append entries, so the hash chain of the real LedgerService stays valid. */
class LedgerReversalChainTest {

    private final List<LedgerEntry> stored = new ArrayList<>();
    private LedgerService ledgerService;
    private Manager manager;

    @BeforeEach
    void setUp() {
        // An append-only stand-in for the ledger table.
        LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
        when(repository.save(any(LedgerEntry.class))).thenAnswer(invocation -> {
            LedgerEntry entry = invocation.getArgument(0);
            ReflectionTestUtils.setField(entry, "id", (long) stored.size() + 1);
            stored.add(entry);
            return entry;
        });
        when(repository.findTopByOrderByIdDesc())
                .thenAnswer(invocation -> stored.isEmpty() ? Optional.empty() : Optional.of(stored.getLast()));
        when(repository.findById(anyLong()))
                .thenAnswer(invocation -> Optional.of(stored.get((int) (long) invocation.getArgument(0) - 1)));
        when(repository.existsByReversesId(anyLong())).thenAnswer(invocation -> stored.stream()
                .anyMatch(e -> e.getReverses() != null && e.getReverses().getId().equals(invocation.getArgument(0))));
        when(repository.findAllByOrderByIdAsc()).thenAnswer(invocation -> List.copyOf(stored));

        manager = new Manager();
        ReflectionTestUtils.setField(manager, "id", 1L);
        ledgerService = new LedgerService(repository);
    }

    @Test
    void ledgerStaysValidAfterDeliveryAndPaymentReversals() {
        LedgerEntry delivery = ledgerService.record(LedgerEntryType.DELIVERY, "DELIVERY", 42L,
                new BigDecimal("100.00"), 500L, "Delivery RCT-7K2Q", manager);
        LedgerEntry payment = ledgerService.record(LedgerEntryType.PAYMENT, "PAYMENT", 9L, null, 200_000L,
                "Payment PAY-0009 by CASH", manager);
        List<String> hashesBefore = stored.stream().map(LedgerEntry::getCurrentHash).toList();

        ledgerService.reverse(delivery.getId(), "Weighed twice", manager);
        ledgerService.reverse(payment.getId(), "Paid twice", manager);

        VerifyResult result = ledgerService.verify();
        assertTrue(result.valid());
        assertEquals(4, result.checkedEntries());
        // The reversed entries are untouched; the reversals were only appended.
        assertEquals(hashesBefore, stored.subList(0, 2).stream().map(LedgerEntry::getCurrentHash).toList());
        assertEquals(new BigDecimal("-100.00"), stored.get(2).getQuantityKg());
        assertEquals(-200_000L, stored.get(3).getAmount());
    }
}
