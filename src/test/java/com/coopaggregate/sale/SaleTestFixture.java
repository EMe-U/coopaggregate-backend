package com.coopaggregate.sale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.batch.BatchRepository;
import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.batch.SaleAllocationRepository;
import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.buyer.BuyerRepository;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotPortion;
import com.coopaggregate.lot.LotPortionRepository;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

/**
 * Two grades, their lots, batches and lot portions, and the repositories behind SaleService standing in for the
 * database: everything is in memory, saved sales and allocations get ids, and every lock and ledger entry is noted in
 * order. Grade Big (id 1) has the older lots A-1 (closed, id 3) and A-2 (open, id 4); grade Small (id 2) has the older
 * lot B-1 (open, id 5). Fixed-size lots are added by tests with fixedLot. Each batch of an older lot is one portion of
 * it, with the delivery's id as the portion's id.
 */
class SaleTestFixture {

    /** A ledger entry written by a sale. */
    record Ledgered(LedgerEntryType type, String relatedType, Long relatedId, BigDecimal kg, Long amount,
                    String reason) {
    }

    final SaleRepository saleRepository = mock(SaleRepository.class);
    final SaleOrderRepository saleOrderRepository = mock(SaleOrderRepository.class);
    final GradeRepository gradeRepository = mock(GradeRepository.class);
    final LotRepository lotRepository = mock(LotRepository.class);
    final BuyerRepository buyerRepository = mock(BuyerRepository.class);
    final LedgerService ledgerService = mock(LedgerService.class);
    final LotPortionRepository lotPortionRepository = mock(LotPortionRepository.class);
    final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    final BatchRepository batchRepository = mock(BatchRepository.class);
    final SaleAllocationRepository saleAllocationRepository = mock(SaleAllocationRepository.class);
    final SettingRepository settingRepository = mock(SettingRepository.class);

    final Grade big = grade(1L, "Big");
    final Grade small = grade(2L, "Small");
    final Map<Long, Lot> lots = new HashMap<>();
    final Lot lotA1 = lot(3L, "A-2026-01", big, LotStatus.CLOSED);
    final Lot lotA2 = lot(4L, "A-2026-02", big, LotStatus.OPEN);
    final Lot lotB1 = lot(5L, "B-2026-01", small, LotStatus.OPEN);
    final Member alice = member(5L, "M-0005", "Uwimana Claudine");
    final Member bosco = member(6L, "M-0006", "Habimana Bosco");
    final Buyer buyer = new Buyer();
    final Manager manager = new Manager();

    final List<Delivery> deliveries = new ArrayList<>();
    final List<LotPortion> portions = new ArrayList<>();
    final List<Sale> savedSales = new ArrayList<>();
    final List<SaleAllocation> savedAllocations = new ArrayList<>();
    final List<SaleOrder> savedOrders = new ArrayList<>();
    final List<Ledgered> ledger = new ArrayList<>();
    /** What was locked and written, in order: grade:1, lot:3, portions, deliveries, ledger:SALE ... */
    final List<String> calls = new ArrayList<>();

    final SaleService service;

    SaleTestFixture() {
        ReflectionTestUtils.setField(buyer, "id", 2L);
        buyer.setName("Kalisa Traders");
        when(buyerRepository.findById(2L)).thenReturn(Optional.of(buyer));
        when(settingRepository.findById(1L)).thenReturn(Optional.of(new Setting()));

        Map<Long, Grade> grades = Map.of(1L, big, 2L, small);
        when(gradeRepository.findForUpdateById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            calls.add("grade:" + id);
            return Optional.ofNullable(grades.get(id));
        });
        lots.put(3L, lotA1);
        lots.put(4L, lotA2);
        lots.put(5L, lotB1);
        when(lotRepository.findForUpdateById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            calls.add("lot:" + id);
            return Optional.ofNullable(lots.get(id));
        });

        // The portions a sale may take from: of confirmed, not reversed deliveries, with kg left.
        when(lotPortionRepository.findLotIdsWithSellable(anyLong())).thenAnswer(invocation -> sellable(
                invocation.getArgument(0)).stream().map(p -> p.getLot().getId()).distinct().sorted().toList());
        when(lotPortionRepository.findSellable(anyLong())).thenAnswer(invocation -> sellable(invocation.getArgument(0)));
        when(lotPortionRepository.lockByIds(anyCollection())).thenAnswer(invocation -> {
            calls.add("portions");
            Collection<Long> ids = invocation.getArgument(0);
            return portions.stream().filter(p -> ids.contains(p.getId())).toList();
        });
        when(deliveryRepository.lockByIds(anyCollection())).thenAnswer(invocation -> {
            calls.add("deliveries");
            Collection<Long> ids = invocation.getArgument(0);
            return deliveries.stream().filter(d -> ids.contains(d.getId())).toList();
        });
        when(lotPortionRepository.findLossCarriers(anyLong())).thenAnswer(invocation -> portions.stream()
                .filter(p -> p.getLot().getId().equals(invocation.getArgument(0)) && !p.getDelivery().isReversed()
                        && p.unallocatedKg().signum() > 0)
                .toList());
        // What a lot still holds, from the portions in memory.
        when(lotPortionRepository.remainingKg(anyLong())).thenAnswer(invocation -> portions.stream()
                .filter(p -> p.getLot().getId().equals(invocation.getArgument(0)) && !p.getDelivery().isReversed())
                .map(LotPortion::unallocatedKg).reduce(BigDecimal.ZERO, BigDecimal::add));
        when(batchRepository.gradeRemainingKgWithStatus(anyLong(), any())).thenAnswer(invocation -> deliveries.stream()
                .filter(d -> d.getGrade().getId().equals(invocation.getArgument(0)))
                .filter(d -> !d.isReversed() && d.getConfirmationStatus() == invocation.<ConfirmationStatus>getArgument(1))
                .map(Delivery::unallocatedKg).reduce(BigDecimal.ZERO, BigDecimal::add));

        AtomicLong saleIds = new AtomicLong(10);
        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> {
            Sale sale = invocation.getArgument(0);
            ReflectionTestUtils.setField(sale, "id", saleIds.incrementAndGet());
            savedSales.add(sale);
            calls.add("sale");
            return sale;
        });
        AtomicLong allocationIds = new AtomicLong(100);
        when(saleAllocationRepository.save(any(SaleAllocation.class))).thenAnswer(invocation -> {
            SaleAllocation allocation = invocation.getArgument(0);
            ReflectionTestUtils.setField(allocation, "id", allocationIds.incrementAndGet());
            savedAllocations.add(allocation);
            calls.add("allocation");
            return allocation;
        });
        when(saleAllocationRepository.findBySaleIdOrderById(anyLong())).thenAnswer(invocation -> savedAllocations.stream()
                .filter(a -> a.getSale().getId().equals(invocation.getArgument(0))).toList());
        when(saleAllocationRepository.findBySaleIdInOrderByIdAsc(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return savedAllocations.stream().filter(a -> ids.contains(a.getSale().getId())).toList();
        });
        AtomicLong orderIds = new AtomicLong(50);
        when(saleOrderRepository.save(any(SaleOrder.class))).thenAnswer(invocation -> {
            SaleOrder order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", orderIds.incrementAndGet());
            savedOrders.add(order);
            return order;
        });
        when(saleRepository.findBySaleOrderIdOrderByIdAsc(anyLong())).thenAnswer(invocation -> savedSales.stream()
                .filter(s -> s.getSaleOrder() != null && s.getSaleOrder().getId().equals(invocation.getArgument(0)))
                .toList());
        doAnswer(invocation -> {
            ledger.add(new Ledgered(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5)));
            calls.add("ledger:" + invocation.getArgument(0));
            return null;
        }).when(ledgerService).record(any(), any(), any(), any(), any(), any(), any());

        service = new SaleService(saleRepository, saleOrderRepository, gradeRepository, lotRepository,
                buyerRepository, ledgerService, batchRepository, saleAllocationRepository, settingRepository,
                lotPortionRepository, deliveryRepository);
    }

    /**
     * A confirmed batch delivered at the given time. In an older lot it is that lot's batch; in a fixed-size lot it has
     * no lot of its own and is in the lot through its portion. Either way it gets one portion, with its own id.
     */
    Delivery batch(Long id, Lot lot, Member member, String deliveredAt, String kg) {
        Delivery delivery = delivery(id, lot.getGrade(), member, deliveredAt, kg);
        if (lot.getCapacityKg() == null) {
            delivery.setLot(lot);
        }
        portion(id, lot, delivery, kg);
        return delivery;
    }

    /** A confirmed delivery that filled the end of one fixed-size lot and started the next: portions id*10 and id*10+1. */
    Delivery splitBatch(Long id, Member member, String deliveredAt, Lot first, String firstKg, Lot second,
                        String secondKg) {
        Delivery delivery = delivery(id, first.getGrade(), member, deliveredAt,
                new BigDecimal(firstKg).add(new BigDecimal(secondKg)).toPlainString());
        portion(id * 10, first, delivery, firstKg);
        portion(id * 10 + 1, second, delivery, secondKg);
        return delivery;
    }

    /** A fixed-size lot of 100 kg. */
    Lot fixedLot(Long id, String code, Grade grade, LotStatus status) {
        Lot lot = lot(id, code, grade, status);
        lot.setCapacityKg(new BigDecimal("100.00"));
        lots.put(id, lot);
        return lot;
    }

    LotPortion portionOf(Delivery delivery, Lot lot) {
        return portions.stream().filter(p -> p.getDelivery() == delivery && p.getLot() == lot).findFirst()
                .orElseThrow();
    }

    private Delivery delivery(Long id, Grade grade, Member member, String deliveredAt, String kg) {
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", id);
        delivery.setReceiptCode("RCT-000" + id);
        delivery.setGrade(grade);
        delivery.setMember(member);
        delivery.setDeliveredAt(Instant.parse(deliveredAt));
        delivery.setQuantityKg(new BigDecimal(kg));
        ReflectionTestUtils.setField(delivery, "confirmationStatus", ConfirmationStatus.CONFIRMED);
        ReflectionTestUtils.setField(delivery, "remainingKg", new BigDecimal(kg));
        deliveries.add(delivery);
        return delivery;
    }

    private void portion(Long id, Lot lot, Delivery delivery, String kg) {
        LotPortion portion = new LotPortion(lot, delivery, new BigDecimal(kg));
        ReflectionTestUtils.setField(portion, "id", id);
        portions.add(portion);
    }

    /** A batch whose member has not confirmed it yet. */
    Delivery pendingBatch(Long id, Lot lot, Member member, String deliveredAt, String kg) {
        Delivery delivery = batch(id, lot, member, deliveredAt, kg);
        ReflectionTestUtils.setField(delivery, "confirmationStatus", ConfirmationStatus.PENDING);
        return delivery;
    }

    /** The usual stock of grade Big: a batch in each of its two lots, the older one in the later lot. */
    void stockOfBig() {
        batch(1L, lotA1, alice, "2026-10-01T08:00:00Z", "100.00");
        batch(2L, lotA2, bosco, "2026-10-01T07:00:00Z", "50.00");
        batch(3L, lotA1, alice, "2026-10-02T08:00:00Z", "100.00");
    }

    List<Ledgered> credits() {
        return ledger.stream().filter(entry -> entry.type() == LedgerEntryType.SALE_CREDIT).toList();
    }

    private List<LotPortion> sellable(Long gradeId) {
        return portions.stream()
                .filter(p -> p.getLot().getGrade().getId().equals(gradeId))
                .filter(p -> !p.getDelivery().isReversed()
                        && p.getDelivery().getConfirmationStatus() == ConfirmationStatus.CONFIRMED
                        && p.unallocatedKg().signum() > 0)
                .toList();
    }

    private static Grade grade(Long id, String name) {
        Grade grade = new Grade();
        ReflectionTestUtils.setField(grade, "id", id);
        grade.setCode(name.substring(0, 1));
        grade.setName(name);
        return grade;
    }

    private static Lot lot(Long id, String code, Grade grade, LotStatus status) {
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", id);
        lot.setLotCode(code);
        lot.setGrade(grade);
        lot.setStatus(status);
        return lot;
    }

    private static Member member(Long id, String code, String name) {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", id);
        member.setMemberCode(code);
        member.setFullName(name);
        return member;
    }

}
