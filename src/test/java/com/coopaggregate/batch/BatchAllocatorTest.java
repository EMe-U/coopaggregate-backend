package com.coopaggregate.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.loss.Loss;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.sale.Sale;

/**
 * Losses take kg from the lot's oldest batches first, and sales take kg from the oldest batches too (SalePlanner,
 * as SaleService does). After every step the batches must add up to the lot's own totals, computed here
 * independently: delivered (not reversed) minus lost minus sold.
 */
class BatchAllocatorTest {

    private final List<Delivery> deliveries = new ArrayList<>();
    private final List<SaleAllocation> saleAllocations = new ArrayList<>();
    private final List<LossAllocation> lossAllocations = new ArrayList<>();
    private final List<Sale> sales = new ArrayList<>();
    private final List<Loss> losses = new ArrayList<>();
    private Lot lot;
    private BatchAllocator allocator;

    @BeforeEach
    void setUp() {
        lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode("A-2026-01");

        // Stands in for the database: the lot's batches with kg left, oldest first, as the FIFO query returns them.
        BatchRepository batchRepository = mock(BatchRepository.class);
        when(batchRepository.lockBatchesWithKgLeft(anyLong())).thenAnswer(invocation -> deliveries.stream()
                .filter(d -> d.getLot().getId().equals(invocation.getArgument(0)))
                .filter(d -> !d.isReversed() && d.unallocatedKg().signum() > 0)
                .sorted(Comparator.comparing(Delivery::getDeliveredAt).thenComparing(Delivery::getId))
                .toList());
        LossAllocationRepository lossAllocationRepository = mock(LossAllocationRepository.class);
        when(lossAllocationRepository.save(any(LossAllocation.class))).thenAnswer(invocation -> {
            lossAllocations.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(lossAllocationRepository.findByDeliveryIdOrderById(anyLong())).thenAnswer(invocation ->
                lossAllocations.stream().filter(a -> a.getDelivery().getId().equals(invocation.getArgument(0)))
                        .toList());
        when(lossAllocationRepository.findByLossIdAndDeliveryId(anyLong(), anyLong())).thenAnswer(invocation ->
                lossAllocations.stream()
                        .filter(a -> a.getLoss().getId().equals(invocation.getArgument(0))
                                && a.getDelivery().getId().equals(invocation.getArgument(1)))
                        .findFirst());
        doAnswer(invocation -> {
            lossAllocations.removeAll(invocation.<List<LossAllocation>>getArgument(0));
            return null;
        }).when(lossAllocationRepository).deleteAll(any());

        allocator = new BatchAllocator(batchRepository, lossAllocationRepository);
    }

    @Test
    void saleTakesTheOldestBatchFirst() {
        Delivery newer = delivery(1L, "2026-10-20T08:00:00Z", "100.00");
        Delivery older = delivery(2L, "2026-10-19T08:00:00Z", "100.00");

        sell("60.00");

        assertEquals(new BigDecimal("60.00"), older.getSoldKg());
        assertEquals(BigDecimal.ZERO, newer.getSoldKg());
        assertTotalsMatchTheLot();
    }

    @Test
    void sameDeliveryTimeUsesTheSmallerIdFirst() {
        Delivery second = delivery(8L, "2026-10-19T08:00:00Z", "50.00");
        Delivery first = delivery(7L, "2026-10-19T08:00:00Z", "50.00");

        sell("30.00");

        assertEquals(new BigDecimal("30.00"), first.getSoldKg());
        assertEquals(BigDecimal.ZERO, second.getSoldKg());
    }

    @Test
    void saleSpreadsOverBatchesAndRecordsEachOne() {
        Delivery a = delivery(1L, "2026-10-19T08:00:00Z", "100.00");
        Delivery b = delivery(2L, "2026-10-19T09:00:00Z", "80.50");
        Delivery c = delivery(3L, "2026-10-19T10:00:00Z", "40.00");

        Sale sale = sell("150.25");

        assertEquals(new BigDecimal("100.00"), a.getSoldKg());
        assertEquals(new BigDecimal("50.25"), b.getSoldKg());
        assertEquals(new BigDecimal("30.25"), b.unallocatedKg());
        assertEquals(BigDecimal.ZERO, c.getSoldKg());
        assertEquals(List.of("1:100.00", "2:50.25"), saleAllocations.stream()
                .filter(alloc -> alloc.getSale() == sale)
                .map(alloc -> alloc.getDelivery().getId() + ":" + alloc.getQuantityKg()).toList());
        assertTotalsMatchTheLot();
    }

    @Test
    void saleOfExactlyTheLastKgEmptiesEveryBatch() {
        delivery(1L, "2026-10-19T08:00:00Z", "100.00");
        delivery(2L, "2026-10-19T09:00:00Z", "80.50");

        sell("180.50");

        deliveries.forEach(d -> assertEquals(0, d.unallocatedKg().signum()));
        assertTotalsMatchTheLot();
    }

    @Test
    void reversedDeliveriesAreNotUsed() {
        Delivery reversed = delivery(1L, "2026-10-19T08:00:00Z", "100.00");
        reversed.markReversed(reversal());
        Delivery kept = delivery(2L, "2026-10-19T09:00:00Z", "100.00");

        sell("40.00");

        assertEquals(BigDecimal.ZERO, reversed.getSoldKg());
        assertEquals(new BigDecimal("40.00"), kept.getSoldKg());
    }

    @Test
    void lossTakesTheOldestBatchFirstToo() {
        Delivery older = delivery(1L, "2026-10-19T08:00:00Z", "100.00");
        delivery(2L, "2026-10-19T09:00:00Z", "100.00");

        lose("12.50");

        assertEquals(new BigDecimal("12.50"), older.getLostKg());
        assertEquals(new BigDecimal("87.50"), older.unallocatedKg());
        assertTotalsMatchTheLot();
    }

    @Test
    void moreKgThanTheBatchesHoldIsRefused() {
        delivery(1L, "2026-10-19T08:00:00Z", "100.00");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> lose("100.01"));

        assertEquals("The batches of lot A-2026-01 are 0.01 kg short of the lot's total. Nothing was saved.",
                e.getMessage());
    }

    @Test
    void aLossAfterASaleOnlyTakesWhatTheSaleLeft() {
        Delivery older = delivery(1L, "2026-10-19T08:00:00Z", "100.00");
        Delivery newer = delivery(2L, "2026-10-19T09:00:00Z", "100.00");

        sell("70.00");
        lose("50.00");

        assertEquals(new BigDecimal("70.00"), older.getSoldKg());
        assertEquals(new BigDecimal("30.00"), older.getLostKg());
        assertEquals(new BigDecimal("20.00"), newer.getLostKg());
        assertEquals(0, older.unallocatedKg().signum());
        assertTotalsMatchTheLot();
    }

    @Test
    void aSaleAfterALossOnlyTakesWhatTheLossLeft() {
        Delivery older = delivery(1L, "2026-10-19T08:00:00Z", "100.00");
        Delivery newer = delivery(2L, "2026-10-19T09:00:00Z", "100.00");

        lose("60.00");
        sell("60.00");

        assertEquals(new BigDecimal("60.00"), older.getLostKg());
        assertEquals(new BigDecimal("40.00"), older.getSoldKg());
        assertEquals(new BigDecimal("20.00"), newer.getSoldKg());
        assertTotalsMatchTheLot();
    }

    @Test
    void reversedDeliveryHandsItsLostKgToTheOtherBatches() {
        Delivery first = delivery(1L, "2026-10-19T08:00:00Z", "100.00");
        Delivery second = delivery(2L, "2026-10-19T09:00:00Z", "100.00");
        Delivery third = delivery(3L, "2026-10-19T10:00:00Z", "100.00");
        Loss rot = lose("120.00");

        first.markReversed(reversal());
        allocator.releaseLosses(first);

        assertEquals(BigDecimal.ZERO, first.getLostKg());
        assertEquals(new BigDecimal("100.00"), second.getLostKg());
        assertEquals(new BigDecimal("20.00"), third.getLostKg());
        // The 20 kg that were already on the second batch and the 80 kg moved there are one allocation row.
        assertEquals(List.of("2:100.00", "3:20.00"), lossAllocations.stream()
                .filter(alloc -> alloc.getLoss() == rot)
                .map(alloc -> alloc.getDelivery().getId() + ":" + alloc.getQuantityKg()).sorted().toList());
        assertTotalsMatchTheLot();
    }

    @Test
    void reversingADeliveryWithoutLossesChangesNoOtherBatch() {
        Delivery first = delivery(1L, "2026-10-19T08:00:00Z", "100.00");
        Delivery second = delivery(2L, "2026-10-19T09:00:00Z", "100.00");

        first.markReversed(reversal());
        allocator.releaseLosses(first);

        assertEquals(BigDecimal.ZERO, second.getLostKg());
        assertTotalsMatchTheLot();
    }

    @Test
    void aLongMixOfLossesSalesAndAReversalAlwaysMatchesTheLot() {
        delivery(1L, "2026-10-19T08:00:00Z", "120.00");
        Delivery second = delivery(2L, "2026-10-19T09:00:00Z", "75.25");
        delivery(3L, "2026-10-20T07:00:00Z", "200.00");
        delivery(4L, "2026-10-21T07:00:00Z", "33.33");
        delivery(5L, "2026-10-19T08:00:00Z", "10.00");

        lose("15.00");
        assertTotalsMatchTheLot();
        lose("110.00");
        assertTotalsMatchTheLot();
        second.markReversed(reversal());
        allocator.releaseLosses(second);
        assertTotalsMatchTheLot();
        sell("100.00");
        assertTotalsMatchTheLot();
        lose("0.33");
        assertTotalsMatchTheLot();
        sell("138.00");
        assertTotalsMatchTheLot();
        assertEquals(0, remainingOfBatches().signum());
    }

    // Σ of the batches equals the lot: received = delivered, sold, lost, and remaining = delivered - lost - sold.
    private void assertTotalsMatchTheLot() {
        List<Delivery> batches = deliveries.stream().filter(d -> !d.isReversed()).toList();
        BigDecimal delivered = sum(batches.stream().map(Delivery::getQuantityKg).toList());
        BigDecimal sold = sum(sales.stream().map(Sale::getQuantityKg).toList());
        BigDecimal lost = sum(losses.stream().map(Loss::getQuantityKg).toList());

        assertEquals(0, sold.compareTo(sum(batches.stream().map(Delivery::getSoldKg).toList())), "sold kg");
        assertEquals(0, lost.compareTo(sum(batches.stream().map(Delivery::getLostKg).toList())), "lost kg");
        assertEquals(0, delivered.subtract(lost).subtract(sold).compareTo(remainingOfBatches()), "remaining kg");
        assertEquals(0, sold.compareTo(sum(saleAllocations.stream().map(SaleAllocation::getQuantityKg).toList())));
        assertEquals(0, lost.compareTo(sum(lossAllocations.stream().map(LossAllocation::getQuantityKg).toList())));
    }

    private BigDecimal remainingOfBatches() {
        return sum(deliveries.stream().filter(d -> !d.isReversed()).map(Delivery::unallocatedKg).toList());
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Delivery delivery(Long id, String deliveredAt, String kg) {
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", id);
        delivery.setLot(lot);
        delivery.setDeliveredAt(Instant.parse(deliveredAt));
        delivery.setQuantityKg(new BigDecimal(kg));
        deliveries.add(delivery);
        return delivery;
    }

    // A sale the way SaleService makes it: SalePlanner picks the oldest batches with kg left, each one's sold kg go up
    // and an allocation with its money is saved.
    private Sale sell(String kg) {
        Sale sale = new Sale();
        ReflectionTestUtils.setField(sale, "id", (long) sales.size() + 1);
        sale.setLot(lot);
        sale.setQuantityKg(new BigDecimal(kg));
        List<Delivery> sellable = deliveries.stream()
                .filter(d -> !d.isReversed() && d.unallocatedKg().signum() > 0)
                .sorted(Comparator.comparing(Delivery::getDeliveredAt).thenComparing(Delivery::getId))
                .toList();
        SalePlan plan = SalePlanner.plan(sellable.stream()
                .map(d -> new SellableBatch(d.getId(), null, lot.getId(), lot.getLotCode(), null, null, null,
                        d.getDeliveredAt(), d.unallocatedKg()))
                .toList(), new BigDecimal(kg), 300, 5, List.of());
        if (!plan.enough()) {
            throw new IllegalStateException("not enough kg");
        }
        for (SalePlan.Line line : plan.lines()) {
            Delivery batch = sellable.stream().filter(d -> d.getId().equals(line.batch().deliveryId())).findFirst()
                    .orElseThrow();
            batch.addSoldKg(line.kg());
            saleAllocations.add(new SaleAllocation(sale, batch, line.kg(), line.grossRwf(), line.deductionRwf()));
        }
        sales.add(sale);
        return sale;
    }

    private Loss lose(String kg) {
        Loss loss = new Loss();
        ReflectionTestUtils.setField(loss, "id", (long) losses.size() + 1);
        loss.setLot(lot);
        loss.setQuantityKg(new BigDecimal(kg));
        allocator.allocateLoss(loss);
        losses.add(loss);
        return loss;
    }

    private static LedgerEntry reversal() {
        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryDate(Instant.parse("2026-10-22T08:00:00Z"));
        reversal.setReason("Recorded by mistake");
        return reversal;
    }
}
