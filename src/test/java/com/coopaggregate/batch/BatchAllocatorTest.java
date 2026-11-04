package com.coopaggregate.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.delivery.ConfirmationMethod;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.DisputeReason;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.loss.Loss;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotPortion;
import com.coopaggregate.lot.LotPortionRepository;
import com.coopaggregate.member.Member;

/** The repositories stand in for the database: lots, portions, deliveries and loss allocations are in memory. */
class BatchAllocatorTest {

    private final List<LotPortion> portions = new ArrayList<>();
    private final List<LossAllocation> lossAllocations = new ArrayList<>();
    private final List<Loss> losses = new ArrayList<>();
    private LotPortionRepository lotPortionRepository;
    private DeliveryRepository deliveryRepository;
    private Lot lot;
    private Lot next;
    private long nextPortionId = 1;
    private BatchAllocator allocator;

    @BeforeEach
    void setUp() {
        lot = lot(3L, "A-2026-01");
        next = lot(4L, "A-2026-02");

        lotPortionRepository = mock(LotPortionRepository.class);
        // As the query: the lot's portions with kg left of deliveries that are not reversed (the status is checked by
        // the allocator too).
        when(lotPortionRepository.findLossCarriers(anyLong())).thenAnswer(invocation -> portions.stream()
                .filter(p -> p.getLot().getId().equals(invocation.getArgument(0)) && !p.getDelivery().isReversed()
                        && p.unallocatedKg().signum() > 0)
                .toList());
        when(lotPortionRepository.findByDeliveryIdOrderById(anyLong())).thenAnswer(invocation -> portions.stream()
                .filter(p -> p.getDelivery().getId().equals(invocation.getArgument(0))).toList());
        deliveryRepository = mock(DeliveryRepository.class);

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

        allocator = new BatchAllocator(lotPortionRepository, lossAllocationRepository, deliveryRepository);
    }

    @Test
    void aLossIsSharedInProportionToWhatEachMemberHasLeftInTheLot() {
        Delivery alice = delivery(1L, "2026-10-01T08:00:00Z", lot, "20.00");
        Delivery bosco = delivery(2L, "2026-10-02T08:00:00Z", lot, "50.00");
        Delivery claude = delivery(3L, "2026-10-03T08:00:00Z", lot, "30.00");

        allocator.allocateLoss(loss(lot, "10.00"));

        assertEquals(List.of(new BigDecimal("2.00"), new BigDecimal("5.00"), new BigDecimal("3.00")),
                List.of(alice.getLostKg(), bosco.getLostKg(), claude.getLostKg()));
        assertEquals(3, lossAllocations.size());
        lossAllocations.forEach(a -> assertTrue(a.getLotPortion() != null && a.getLotPortion().getDelivery() == a.getDelivery()));
        assertLotsMatchTheirLosses();
    }

    @Test
    void kgAlreadySoldNeverCarryALoss() {
        Delivery soldOut = delivery(1L, "2026-10-01T08:00:00Z", lot, "60.00");
        Delivery halfSold = delivery(2L, "2026-10-02T08:00:00Z", lot, "40.00");
        sell(soldOut, lot, "60.00");
        sell(halfSold, lot, "20.00");

        assertEquals(new BigDecimal("20.00"), allocator.lossCarryingKg(lot));
        allocator.allocateLoss(loss(lot, "5.00"));

        assertEquals(BigDecimal.ZERO, soldOut.getLostKg());
        assertEquals(new BigDecimal("5.00"), halfSold.getLostKg());
    }

    @Test
    void disputedKgCarryALossButWaitingKgDoNot() {
        Delivery confirmed = delivery(1L, "2026-10-01T08:00:00Z", lot, "30.00");
        Delivery disputed = delivery(2L, "2026-10-02T08:00:00Z", lot, "30.00");
        disputed.dispute(DisputeReason.WRONG_KG, null, Instant.now());
        // Only lots from before fixed-size lots hold waiting kg.
        Delivery waiting = delivery(3L, "2026-10-03T08:00:00Z", lot, "40.00");
        ReflectionTestUtils.setField(waiting, "confirmationStatus", com.coopaggregate.delivery.ConfirmationStatus.PENDING);

        assertEquals(new BigDecimal("60.00"), allocator.lossCarryingKg(lot));
        allocator.allocateLoss(loss(lot, "6.00"));

        assertEquals(new BigDecimal("3.00"), confirmed.getLostKg());
        assertEquals(new BigDecimal("3.00"), disputed.getLostKg());
        assertEquals(BigDecimal.ZERO, waiting.getLostKg());
    }

    @Test
    void theLastHundredthsGoToTheLargestRemainderThenTheOlderDelivery() {
        Delivery older = delivery(1L, "2026-10-01T08:00:00Z", lot, "5.00");
        Delivery middle = delivery(2L, "2026-10-02T08:00:00Z", lot, "5.00");
        Delivery newer = delivery(3L, "2026-10-03T08:00:00Z", lot, "5.00");

        allocator.allocateLoss(loss(lot, "1.00"));

        assertEquals(List.of(new BigDecimal("0.34"), new BigDecimal("0.33"), new BigDecimal("0.33")),
                List.of(older.getLostKg(), middle.getLostKg(), newer.getLostKg()));
    }

    @Test
    void aSplitDeliveryOnlyLosesInTheLotTheLossIsIn() {
        Delivery split = delivery(1L, "2026-10-01T08:00:00Z", lot, "30.00");
        portion(split, next, "20.00");
        split.setQuantityKg(new BigDecimal("50.00"));
        delivery(2L, "2026-10-02T08:00:00Z", next, "80.00");

        allocator.allocateLoss(loss(next, "10.00"));

        assertEquals(BigDecimal.ZERO, portionOf(split, lot).getLostKg());
        assertEquals(new BigDecimal("2.00"), portionOf(split, next).getLostKg());
        assertEquals(new BigDecimal("2.00"), split.getLostKg());
    }

    @Test
    void theRowsAreLockedBeforeAnythingIsWritten() {
        delivery(1L, "2026-10-01T08:00:00Z", lot, "30.00");
        delivery(2L, "2026-10-02T08:00:00Z", lot, "30.00");

        allocator.allocateLoss(loss(lot, "6.00"));

        InOrder order = inOrder(lotPortionRepository, deliveryRepository);
        order.verify(lotPortionRepository).findLossCarriers(3L);
        order.verify(lotPortionRepository).lockByIds(List.of(1L, 2L));
        order.verify(deliveryRepository).lockByIds(List.of(1L, 2L));
    }

    @Test
    void moreKgThanTheLotCanCarryIsRefused() {
        delivery(1L, "2026-10-01T08:00:00Z", lot, "100.00");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> allocator.allocateLoss(loss(lot, "100.01")));

        assertEquals("Lot A-2026-01 holds only 100.00 kg that can carry a loss, not 100.01 kg. Nothing was saved.",
                e.getMessage());
    }

    @Test
    void aReversedDeliveryHandsItsLostKgToTheLotsOtherPortionsInProportion() {
        Delivery reversed = delivery(1L, "2026-10-01T08:00:00Z", lot, "50.00");
        Delivery bosco = delivery(2L, "2026-10-02T08:00:00Z", lot, "30.00");
        Delivery claude = delivery(3L, "2026-10-03T08:00:00Z", lot, "20.00");
        allocator.allocateLoss(loss(lot, "10.00"));
        reverse(reversed);

        allocator.releaseLosses(reversed);

        assertEquals(BigDecimal.ZERO, reversed.getLostKg());
        assertEquals(BigDecimal.ZERO, portionOf(reversed, lot).getLostKg());
        // 5 kg handed back: 30 and 20 kg... less their own 3 and 2 kg lost: 27 and 18 left, so 3 and 2 kg more.
        assertEquals(new BigDecimal("6.00"), bosco.getLostKg());
        assertEquals(new BigDecimal("4.00"), claude.getLostKg());
        assertEquals(2, lossAllocations.size(), "added to their existing allocations, not new ones");
        assertLotsMatchTheirLosses();
    }

    @Test
    void reversingADeliveryWithoutLossesChangesNoOtherPortion() {
        Delivery reversed = delivery(1L, "2026-10-01T08:00:00Z", lot, "50.00");
        Delivery other = delivery(2L, "2026-10-02T08:00:00Z", lot, "30.00");
        reverse(reversed);

        allocator.releaseLosses(reversed);

        assertEquals(BigDecimal.ZERO, other.getLostKg());
        assertTrue(lossAllocations.isEmpty());
    }

    @Test
    void aLongMixOfLossesSalesAndReversalsAlwaysMatchesTheLots() {
        Random random = new Random(5);
        List<Delivery> deliveries = new ArrayList<>();
        for (long id = 1; id <= 40; id++) {
            Lot in = id % 3 == 0 ? next : lot;
            deliveries.add(delivery(id, "2026-10-01T08:00:00Z", in,
                    BigDecimal.valueOf(100 + random.nextInt(9_000), 2).toPlainString()));
        }
        for (int step = 0; step < 300; step++) {
            Lot target = random.nextBoolean() ? lot : next;
            BigDecimal carrying = allocator.lossCarryingKg(target);
            int action = random.nextInt(3);
            if (action == 0 && carrying.signum() > 0) {
                BigDecimal kg = BigDecimal.valueOf(1 + random.nextInt(Math.min(carrying.movePointRight(2).intValueExact(),
                        2_000)), 2);
                allocator.allocateLoss(loss(target, kg.toPlainString()));
            } else if (action == 1) {
                Delivery delivery = deliveries.get(random.nextInt(deliveries.size()));
                LotPortion portion = portionOf(delivery, delivery == null ? lot : portions.stream()
                        .filter(p -> p.getDelivery() == delivery).findFirst().orElseThrow().getLot());
                BigDecimal left = portion.unallocatedKg();
                if (!delivery.isReversed() && left.signum() > 0) {
                    sell(delivery, portion.getLot(), BigDecimal.valueOf(1 + random.nextInt(left.movePointRight(2)
                            .intValueExact()), 2).toPlainString());
                }
            } else {
                Delivery delivery = deliveries.get(random.nextInt(deliveries.size()));
                LotPortion portion = portions.stream().filter(p -> p.getDelivery() == delivery).findFirst().orElseThrow();
                BigDecimal othersCarry = allocator.lossCarryingKg(portion.getLot()).subtract(portion.unallocatedKg());
                // As the reversal checks first: nothing sold, and the others can take its lost kg.
                if (!delivery.isReversed() && delivery.getSoldKg().signum() == 0
                        && othersCarry.compareTo(delivery.getLostKg()) >= 0) {
                    reverse(delivery);
                    allocator.releaseLosses(delivery);
                }
            }
            assertLotsMatchTheirLosses();
        }
    }

    // Each lot's portions of deliveries that are not reversed carry exactly the lot's losses; every portion and delivery
    // agrees with its allocations and never holds more sold and lost kg than it received.
    private void assertLotsMatchTheirLosses() {
        for (Lot each : List.of(lot, next)) {
            BigDecimal lost = losses.stream().filter(loss -> loss.getLot() == each).map(Loss::getQuantityKg)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal carried = portions.stream().filter(p -> p.getLot() == each && !p.getDelivery().isReversed())
                    .map(LotPortion::getLostKg).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, lost.compareTo(carried), "lot " + each.getLotCode());
        }
        for (LotPortion portion : portions) {
            assertTrue(portion.unallocatedKg().signum() >= 0, portion.toString());
        }
        for (Delivery delivery : portions.stream().map(LotPortion::getDelivery).distinct().toList()) {
            BigDecimal portionsLost = portions.stream().filter(p -> p.getDelivery() == delivery)
                    .map(LotPortion::getLostKg).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal allocated = lossAllocations.stream().filter(a -> a.getDelivery() == delivery)
                    .map(LossAllocation::getQuantityKg).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, delivery.getLostKg().compareTo(portionsLost));
            assertEquals(0, delivery.getLostKg().compareTo(allocated));
        }
    }

    private Delivery delivery(Long id, String deliveredAt, Lot in, String kg) {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", id + 100);
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", id);
        delivery.setMember(member);
        delivery.setReceiptCode("RCT-000" + id);
        delivery.setDeliveredAt(Instant.parse(deliveredAt));
        delivery.setQuantityKg(new BigDecimal(kg));
        delivery.confirm(ConfirmationMethod.OTP, Instant.now());
        portion(delivery, in, kg);
        return delivery;
    }

    private void portion(Delivery delivery, Lot in, String kg) {
        LotPortion portion = new LotPortion(in, delivery, new BigDecimal(kg));
        ReflectionTestUtils.setField(portion, "id", nextPortionId++);
        portions.add(portion);
    }

    private LotPortion portionOf(Delivery delivery, Lot in) {
        return portions.stream().filter(p -> p.getDelivery() == delivery && p.getLot() == in).findFirst().orElseThrow();
    }

    private void sell(Delivery delivery, Lot in, String kg) {
        portionOf(delivery, in).addSoldKg(new BigDecimal(kg));
        delivery.addSoldKg(new BigDecimal(kg));
    }

    private static void reverse(Delivery delivery) {
        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryDate(Instant.now());
        delivery.markReversed(reversal);
    }

    private Loss loss(Lot in, String kg) {
        Loss loss = new Loss();
        ReflectionTestUtils.setField(loss, "id", (long) losses.size() + 1);
        loss.setLot(in);
        loss.setQuantityKg(new BigDecimal(kg));
        losses.add(loss);
        return loss;
    }

    private static Lot lot(Long id, String code) {
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", id);
        lot.setLotCode(code);
        lot.setCapacityKg(new BigDecimal("100000.00"));
        return lot;
    }
}
