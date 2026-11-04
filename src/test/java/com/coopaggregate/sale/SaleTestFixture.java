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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.batch.BatchRepository;
import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.batch.SaleAllocationRepository;
import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.buyer.BuyerRepository;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

/**
 * Two grades, their lots and batches, and the repositories behind SaleService standing in for the database: the
 * batches are in memory, saved sales and allocations get ids, and every lock and ledger entry is noted in order.
 * Grade Big (id 1) has lots A-1 (closed, id 3) and A-2 (open, id 4); grade Small (id 2) has lot B-1 (open, id 5).
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
    final LotService lotService = mock(LotService.class);
    final LedgerService ledgerService = mock(LedgerService.class);
    final BatchRepository batchRepository = mock(BatchRepository.class);
    final SaleAllocationRepository saleAllocationRepository = mock(SaleAllocationRepository.class);
    final SettingRepository settingRepository = mock(SettingRepository.class);
    final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);

    final Grade big = grade(1L, "Big");
    final Grade small = grade(2L, "Small");
    final Lot lotA1 = lot(3L, "A-2026-01", big, LotStatus.CLOSED);
    final Lot lotA2 = lot(4L, "A-2026-02", big, LotStatus.OPEN);
    final Lot lotB1 = lot(5L, "B-2026-01", small, LotStatus.OPEN);
    final Member alice = member(5L, "M-0005", "Uwimana Claudine");
    final Member bosco = member(6L, "M-0006", "Habimana Bosco");
    final Buyer buyer = new Buyer();
    final Manager manager = new Manager();

    final List<Delivery> deliveries = new ArrayList<>();
    final List<Sale> savedSales = new ArrayList<>();
    final List<SaleAllocation> savedAllocations = new ArrayList<>();
    final List<SaleOrder> savedOrders = new ArrayList<>();
    final List<Ledgered> ledger = new ArrayList<>();
    final List<AuditLog> audits = new ArrayList<>();
    /** What was locked and written, in order: grade:1, lot:3, batches, ledger:SALE ... */
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
        Map<Long, Lot> lots = Map.of(3L, lotA1, 4L, lotA2, 5L, lotB1);
        when(lotRepository.findForUpdateById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            calls.add("lot:" + id);
            return Optional.ofNullable(lots.get(id));
        });

        // The batches a sale may take from: confirmed, not reversed and with kg left.
        when(batchRepository.findLotIdsWithSellableBatches(anyLong())).thenAnswer(invocation -> sellable().stream()
                .filter(d -> d.getLot().getGrade().getId().equals(invocation.getArgument(0)))
                .map(d -> d.getLot().getId()).distinct().sorted().toList());
        when(batchRepository.lockSellableBatches(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> lotIds = invocation.getArgument(0);
            calls.add("batches");
            return sellable().stream()
                    .filter(d -> lotIds.contains(d.getLot().getId()))
                    .sorted(Comparator.comparing(Delivery::getDeliveredAt).thenComparing(Delivery::getId))
                    .peek(d -> ReflectionTestUtils.setField(d, "remainingKg", d.unallocatedKg()))
                    .toList();
        });
        when(batchRepository.gradeRemainingKgWithStatus(anyLong(), any())).thenAnswer(invocation -> deliveries.stream()
                .filter(d -> d.getLot().getGrade().getId().equals(invocation.getArgument(0)))
                .filter(d -> !d.isReversed() && d.getConfirmationStatus() == invocation.<ConfirmationStatus>getArgument(1))
                .map(Delivery::unallocatedKg).reduce(BigDecimal.ZERO, BigDecimal::add));

        // What the lot has left, from the batches in memory.
        when(lotService.quantitiesOf(anyList())).thenAnswer(invocation -> {
            Map<Long, LotQuantities> quantities = new HashMap<>();
            for (Lot lot : invocation.<List<Lot>>getArgument(0)) {
                List<Delivery> batches = deliveries.stream()
                        .filter(d -> d.getLot() == lot && !d.isReversed()).toList();
                quantities.put(lot.getId(), new LotQuantities(lot.getId(), sum(batches, Delivery::getQuantityKg),
                        sum(batches, Delivery::getLostKg), sum(batches, Delivery::getSoldKg), 0L, 0L));
            }
            return quantities;
        });

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
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> {
            audits.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        service = new SaleService(saleRepository, saleOrderRepository, gradeRepository, lotRepository,
                buyerRepository, lotService, ledgerService, batchRepository, saleAllocationRepository,
                settingRepository, auditLogRepository);
    }

    /** A confirmed batch of a lot, delivered at the given time. */
    Delivery batch(Long id, Lot lot, Member member, String deliveredAt, String kg) {
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", id);
        delivery.setReceiptCode("RCT-000" + id);
        delivery.setLot(lot);
        delivery.setMember(member);
        delivery.setDeliveredAt(Instant.parse(deliveredAt));
        delivery.setQuantityKg(new BigDecimal(kg));
        ReflectionTestUtils.setField(delivery, "confirmationStatus", ConfirmationStatus.CONFIRMED);
        ReflectionTestUtils.setField(delivery, "remainingKg", new BigDecimal(kg));
        deliveries.add(delivery);
        return delivery;
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

    private List<Delivery> sellable() {
        return deliveries.stream()
                .filter(d -> !d.isReversed() && d.getConfirmationStatus() == ConfirmationStatus.CONFIRMED
                        && d.unallocatedKg().signum() > 0)
                .toList();
    }

    private static BigDecimal sum(List<Delivery> batches, java.util.function.Function<Delivery, BigDecimal> kg) {
        return batches.stream().map(kg).reduce(BigDecimal.ZERO, BigDecimal::add);
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

    private static <T> List<T> anyList() {
        return org.mockito.ArgumentMatchers.anyList();
    }
}
