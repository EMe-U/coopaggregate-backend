package com.coopaggregate.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.batch.BatchAllocator;
import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.batch.SaleAllocationRepository;
import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.buyer.BuyerRepository;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

class SaleServiceTest {

    private BatchAllocator batchAllocator;
    private SaleAllocationRepository saleAllocationRepository;

    private static final UUID CLIENT_UUID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private SaleRepository saleRepository;
    private BuyerRepository buyerRepository;
    private LedgerService ledgerService;
    private Grade grade;
    private Lot lot;
    private Manager manager;
    private SaleService service;

    @BeforeEach
    void setUp() {
        saleRepository = mock(SaleRepository.class);
        buyerRepository = mock(BuyerRepository.class);
        LotRepository lotRepository = mock(LotRepository.class);
        LotService lotService = mock(LotService.class);
        ledgerService = mock(LedgerService.class);

        grade = new Grade();
        grade.setCode("A");
        grade.setName("Big");
        lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode("A-2026-01");
        lot.setGrade(grade);
        lot.setStatus(LotStatus.CLOSED);
        when(lotRepository.findForUpdateById(3L)).thenReturn(Optional.of(lot));
        // 1000 delivered, 25.50 lost, 400 sold: 574.50 kg remain.
        when(lotService.quantitiesOf(lot)).thenReturn(new LotQuantities(3L, new BigDecimal("1000.00"),
                new BigDecimal("25.50"), new BigDecimal("400.00"), 120000L, 4L));

        Buyer buyer = new Buyer();
        ReflectionTestUtils.setField(buyer, "id", 2L);
        buyer.setName("Kalisa Traders");
        when(buyerRepository.findById(2L)).thenReturn(Optional.of(buyer));

        when(saleRepository.findByClientUuid(any())).thenReturn(Optional.empty());
        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> {
            Sale sale = invocation.getArgument(0);
            ReflectionTestUtils.setField(sale, "id", 11L);
            return sale;
        });

        manager = new Manager();
        batchAllocator = mock(BatchAllocator.class);
        saleAllocationRepository = mock(SaleAllocationRepository.class);
        service = new SaleService(saleRepository, mock(SaleOrderRepository.class), lotRepository, buyerRepository,
                lotService, ledgerService, batchAllocator, saleAllocationRepository);
    }

    @Test
    void saleIsRecordedFromClosedLot() {
        RecordedSale result = sell("100.00", 300L);

        assertTrue(result.created());
        SaleResponse sale = result.sale();
        assertEquals(11L, sale.id());
        assertTrue(sale.saleCode().matches("SAL-[2-9A-HJKMNP-Z]{4}"), sale.saleCode());
        assertEquals("A-2026-01", sale.lotCode());
        assertEquals("Kalisa Traders", sale.buyerName());
        assertEquals(30000L, sale.totalAmountRwf());
        assertEquals(KigaliTime.today(), sale.soldAt());
        assertEquals(LotStatus.CLOSED, sale.lotStatus());
    }

    @ParameterizedTest
    @CsvSource({"100.00, 300, 30000", "120.25, 333, 40043", "0.50, 301, 151", "574.50, 250, 143625"})
    void totalIsKilogramsTimesPriceRoundedToWholeRwf(String quantityKg, long pricePerKg, long expectedTotal) {
        assertEquals(expectedTotal, sell(quantityKg, pricePerKg).sale().totalAmountRwf());
    }

    @Test
    void saleFromOpenLotIsRejected() {
        lot.setStatus(LotStatus.OPEN);

        BadRequestException e = assertThrows(BadRequestException.class, () -> sell("100.00", 300L));

        assertEquals("Lot A-2026-01 is OPEN. Only a closed lot can be sold.", e.getMessage());
        verify(saleRepository, never()).save(any());
    }

    @Test
    void saleFromSoldLotIsRejected() {
        lot.setStatus(LotStatus.SOLD);

        assertThrows(BadRequestException.class, () -> sell("10.00", 300L));
    }

    @Test
    void saleMoreThanRemainingIsRejected() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> sell("574.51", 300L));

        assertEquals("Only 574.50 kg remain in lot A-2026-01.", e.getMessage());
        verify(saleRepository, never()).save(any());
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownBuyerIsRejected() {
        when(buyerRepository.findById(2L)).thenReturn(Optional.empty());

        BadRequestException e = assertThrows(BadRequestException.class, () -> sell("10.00", 300L));

        assertEquals("Buyer 2 does not exist.", e.getMessage());
    }

    @Test
    void lotBecomesSoldWhenNothingRemains() {
        SaleResponse sale = sell("574.50", 300L).sale();

        assertEquals(LotStatus.SOLD, lot.getStatus());
        assertEquals(LotStatus.SOLD, sale.lotStatus());
    }

    @Test
    void lotStaysClosedWhileSomethingRemains() {
        sell("574.49", 300L);

        assertEquals(LotStatus.CLOSED, lot.getStatus());
    }

    @Test
    void priceBelowFloorIsFlaggedButSaved() {
        grade.setFloorPricePerKg(300L);

        RecordedSale result = sell("100.00", 250L);

        assertTrue(result.sale().belowFloor());
        verify(saleRepository).save(any(Sale.class));
    }

    @Test
    void priceAtOrAboveFloorIsNotFlagged() {
        grade.setFloorPricePerKg(300L);

        assertFalse(sell("100.00", 300L).sale().belowFloor());
        assertFalse(sell("100.00", 350L).sale().belowFloor());
    }

    @Test
    void gradeWithoutFloorPriceIsNeverBelowFloor() {
        grade.setFloorPricePerKg(null);

        assertFalse(sell("100.00", 1L).sale().belowFloor());
    }

    @Test
    void sameClientUuidReturnsExistingSale() {
        lot.setStatus(LotStatus.SOLD);
        Sale existing = new Sale();
        ReflectionTestUtils.setField(existing, "id", 7L);
        existing.setSaleCode("SAL-7K2Q");
        existing.setGrade(grade);
        existing.setLot(lot);
        Buyer buyer = new Buyer();
        buyer.setName("Kalisa Traders");
        existing.setBuyer(buyer);
        existing.setQuantityKg(new BigDecimal("574.50"));
        existing.setPricePerKg(300L);
        existing.setTotalAmount(172350L);
        when(saleRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(existing));

        RecordedSale result = sell("574.50", 300L);

        assertFalse(result.created());
        assertEquals(7L, result.sale().id());
        assertEquals("SAL-7K2Q", result.sale().saleCode());
        verify(saleRepository, never()).save(any());
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void saleTakesItsKgFromTheLotsBatches() {
        sell("100.00", 300L);

        ArgumentCaptor<Sale> sale = ArgumentCaptor.forClass(Sale.class);
        verify(batchAllocator).allocateSale(sale.capture());
        assertEquals(new BigDecimal("100.00"), sale.getValue().getQuantityKg());
        assertEquals(11L, sale.getValue().getId());
    }

    @Test
    void saleKeepsTheGradeOfItsLot() {
        sell("100.00", 300L);

        ArgumentCaptor<Sale> sale = ArgumentCaptor.forClass(Sale.class);
        verify(batchAllocator).allocateSale(sale.capture());
        assertSame(grade, sale.getValue().getGrade());
    }

    @Test
    void refusedSaleTakesNoKgFromTheBatches() {
        assertThrows(RuntimeException.class, () -> sell("999999.00", 300L));

        verify(batchAllocator, never()).allocateSale(any());
    }

    @Test
    void saleIsRecordedInLedger() {
        SaleResponse sale = sell("100.00", 300L).sale();

        verify(ledgerService).record(LedgerEntryType.SALE, "SALE", 11L, new BigDecimal("100.00"), 30000L,
                "Sale " + sale.saleCode() + " from A-2026-01 to Kalisa Traders", manager);
    }

    @Test
    void summaryAddsUpThisMonthsSales() {
        LocalDate first = LocalDate.of(2026, 10, 1);
        LocalDate last = LocalDate.of(2026, 10, 31);
        when(saleRepository.totalAmountBetween(first, last)).thenReturn(160250L);
        when(saleRepository.totalKgBetween(first, last)).thenReturn(new BigDecimal("500.75"));
        when(saleRepository.buyerCountBetween(first, last)).thenReturn(3L);

        SaleSummaryResponse summary = service.summary(LocalDate.of(2026, 10, 9));

        assertEquals(new SaleSummaryResponse(160250L, new BigDecimal("500.75"), 3L, new BigDecimal("320.02")),
                summary);
    }

    @Test
    void averagePriceIsZeroWithoutSales() {
        when(saleRepository.totalAmountBetween(any(), any())).thenReturn(0L);
        when(saleRepository.totalKgBetween(any(), any())).thenReturn(BigDecimal.ZERO);

        SaleSummaryResponse summary = service.summary(LocalDate.of(2028, 2, 29));

        assertEquals(BigDecimal.ZERO, summary.averagePricePerKg());
        verify(saleRepository).totalKgBetween(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29));
    }

    @Test
    void saleResponseListsTheBatchesAndWhatEachMemberWasCredited() {
        Sale sale = new Sale();
        ReflectionTestUtils.setField(sale, "id", 7L);
        sale.setSaleCode("SAL-7K2Q");
        sale.setGrade(grade);
        Buyer buyer = new Buyer();
        buyer.setName("Kalisa Traders");
        sale.setBuyer(buyer);
        sale.setQuantityKg(new BigDecimal("150.00"));
        sale.setPricePerKg(300L);
        sale.setTotalAmount(45_000L);
        when(saleRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(sale));
        when(saleAllocationRepository.findBySaleIdOrderById(7L)).thenReturn(List.of(
                credit(sale, 42L, "RCT-0042", "100.00", 30_000L, 500L),
                credit(sale, 43L, "RCT-0043", "50.00", 15_000L, 250L)));

        SaleResponse response = sell("150.00", 300L).sale();

        assertNull(response.lotId(), "a sale by grade has no lot of its own");
        assertEquals(List.of("RCT-0042", "RCT-0043"),
                response.batches().stream().map(SaleResponse.BatchLine::receiptCode).toList());
        assertEquals(new SaleResponse.BatchLine(42L, "RCT-0042", 3L, "A-2026-01", 5L, "M-0005", "Uwimana Claudine",
                new BigDecimal("100.00"), 30_000L, 500L, 29_500L), response.batches().get(0));
        assertEquals(750L, response.totalDeductionRwf());
        assertEquals(44_250L, response.totalNetRwf());
    }

    @Test
    void saleMadeBeforeCreditsShowsItsBatchesWithoutMoney() {
        Sale sale = new Sale();
        ReflectionTestUtils.setField(sale, "id", 7L);
        sale.setGrade(grade);
        sale.setLot(lot);
        sale.setBuyer(new Buyer());
        when(saleRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(sale));
        SaleAllocation old = new SaleAllocation(sale, delivery(42L, "RCT-0042"), new BigDecimal("100.00"));
        when(saleAllocationRepository.findBySaleIdOrderById(7L)).thenReturn(List.of(old));

        SaleResponse response = sell("100.00", 300L).sale();

        assertEquals("A-2026-01", response.lotCode());
        assertEquals(1, response.batches().size());
        assertNull(response.batches().get(0).netRwf());
        assertNull(response.totalNetRwf(), "its members are paid through the lot share");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listBuildsTheBatchesOfAWholePageInOneQuery() {
        Sale one = new Sale();
        ReflectionTestUtils.setField(one, "id", 7L);
        one.setGrade(grade);
        one.setBuyer(new Buyer());
        Sale two = new Sale();
        ReflectionTestUtils.setField(two, "id", 8L);
        two.setGrade(grade);
        two.setBuyer(new Buyer());
        when(saleRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(one, two)));
        when(saleAllocationRepository.findBySaleIdInOrderByIdAsc(List.of(7L, 8L))).thenReturn(List.of(
                credit(two, 43L, "RCT-0043", "50.00", 15_000L, 250L)));

        PageResponse<SaleResponse> page = service.list(null, null, null, null, 0, 20);

        assertEquals(0, page.content().get(0).batches().size());
        assertEquals(1, page.content().get(1).batches().size());
        verify(saleAllocationRepository).findBySaleIdInOrderByIdAsc(List.of(7L, 8L));
    }

    @Test
    void searchMatchesBuyerNameLotCodeAndSaleCode() {
        Root<Sale> root = deepRoot();
        Path<Object> id = root.get("id");

        CriteriaBuilder cb = applyListFilters("Kalisa", null, root);

        // Buyer name, lot code (the lot of an older sale, or a lot of a sale batches) and sale code.
        verify(cb, times(4)).like(any(), eq("%kalisa%"));
        verify(cb, never()).equal(eq(id), any(Object.class));
    }

    @Test
    void numericSearchAlsoMatchesSaleId() {
        Root<Sale> root = deepRoot();
        Path<Object> id = root.get("id");

        CriteriaBuilder cb = applyListFilters("42", null, root);

        verify(cb).equal(id, 42L);
        verify(cb, times(4)).like(any(), eq("%42%"));
    }

    @Test
    void gradeFilterUsesTheSalesGrade() {
        Root<Sale> root = deepRoot();
        Path<Object> gradeId = root.get("grade").get("id");

        CriteriaBuilder cb = applyListFilters(null, 2L, root);

        verify(cb).equal(gradeId, 2L);
        verify(cb, never()).like(any(), any(String.class));
    }

    @SuppressWarnings("unchecked")
    private CriteriaBuilder applyListFilters(String search, Long gradeId, Root<Sale> root) {
        ArgumentCaptor<Specification<Sale>> spec = ArgumentCaptor.forClass(Specification.class);
        when(saleRepository.findAll(spec.capture(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.list(search, null, null, gradeId, 0, 20);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        spec.getValue().toPredicate(root, mock(CriteriaQuery.class, RETURNS_DEEP_STUBS), cb);
        return cb;
    }

    @SuppressWarnings("unchecked")
    private static Root<Sale> deepRoot() {
        return mock(Root.class, RETURNS_DEEP_STUBS);
    }

    private Delivery delivery(Long id, String receiptCode) {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", 5L);
        member.setMemberCode("M-0005");
        member.setFullName("Uwimana Claudine");
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", id);
        delivery.setReceiptCode(receiptCode);
        delivery.setMember(member);
        delivery.setLot(lot);
        return delivery;
    }

    private SaleAllocation credit(Sale sale, Long deliveryId, String receiptCode, String kg, long gross,
                                  long deduction) {
        SaleAllocation allocation = new SaleAllocation(sale, delivery(deliveryId, receiptCode), new BigDecimal(kg));
        ReflectionTestUtils.setField(allocation, "grossRwf", gross);
        ReflectionTestUtils.setField(allocation, "deductionRwf", deduction);
        return allocation;
    }

    private RecordedSale sell(String quantityKg, long pricePerKg) {
        return service.record(
                new SaleRequest(CLIENT_UUID, 3L, 2L, new BigDecimal(quantityKg), pricePerKg, null), manager);
    }
}
