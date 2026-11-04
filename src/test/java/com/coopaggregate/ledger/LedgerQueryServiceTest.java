package com.coopaggregate.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.batch.SaleAllocationRepository;
import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.loss.Loss;
import com.coopaggregate.loss.LossRepository;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.member.Member;
import com.coopaggregate.payment.ProducePayment;
import com.coopaggregate.payment.ProducePaymentRepository;
import com.coopaggregate.sale.Sale;
import com.coopaggregate.sale.SaleRepository;
import com.coopaggregate.share.LotShare;
import com.coopaggregate.share.LotShareRepository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

class LedgerQueryServiceTest {

    private LedgerEntryRepository ledgerEntryRepository;
    private DeliveryRepository deliveryRepository;
    private LossRepository lossRepository;
    private SaleRepository saleRepository;
    private SaleAllocationRepository saleAllocationRepository;
    private LotShareRepository lotShareRepository;
    private ProducePaymentRepository paymentRepository;
    private LedgerQueryService service;

    private Member alice;
    private Lot lotA;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository = mock(LedgerEntryRepository.class);
        deliveryRepository = mock(DeliveryRepository.class);
        lossRepository = mock(LossRepository.class);
        saleRepository = mock(SaleRepository.class);
        lotShareRepository = mock(LotShareRepository.class);
        paymentRepository = mock(ProducePaymentRepository.class);
        saleAllocationRepository = mock(SaleAllocationRepository.class);
        service = new LedgerQueryService(ledgerEntryRepository, deliveryRepository, lossRepository, saleRepository,
                lotShareRepository, paymentRepository, saleAllocationRepository);

        alice = new Member();
        ReflectionTestUtils.setField(alice, "id", 5L);
        alice.setFullName("Alice Uwase");
        lotA = new Lot();
        ReflectionTestUtils.setField(lotA, "id", 3L);
        lotA.setLotCode("A-2026-01");
    }

    // --- Filters ---

    @Test
    void typeFilterMatchesEntryType() {
        Root<LedgerEntry> root = deepRoot();
        Path<Object> entryType = root.get("entryType");

        CriteriaBuilder cb = applyFilter(new LedgerFilter(LedgerEntryType.SALE, null, null, null, null, null), root);

        verify(cb).equal(entryType, LedgerEntryType.SALE);
    }

    @Test
    void dateFilterUsesWholeKigaliDays() {
        Root<LedgerEntry> root = deepRoot();
        Path<Instant> entryDate = root.get("entryDate");
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 13);

        CriteriaBuilder cb = applyFilter(new LedgerFilter(null, null, null, from, to, null), root);

        verify(cb).greaterThanOrEqualTo(entryDate, from.atStartOfDay(KigaliTime.ZONE).toInstant());
        verify(cb).lessThan(entryDate, LocalDate.of(2026, 10, 14).atStartOfDay(KigaliTime.ZONE).toInstant());
    }

    @Test
    void memberFilterLooksInDeliveriesSharesCreditsAndPayments() {
        CriteriaQuery<?> query = deepQuery();

        CriteriaBuilder cb = applyFilter(new LedgerFilter(null, 5L, null, null, null, null), deepRoot(), query);

        verify(query, times(4)).subquery(Long.class);
        verify(query.subquery(Long.class)).from(Delivery.class);
        verify(query.subquery(Long.class)).from(LotShare.class);
        verify(query.subquery(Long.class)).from(SaleAllocation.class);
        verify(query.subquery(Long.class)).from(ProducePayment.class);
        verify(query.subquery(Long.class), never()).from(Loss.class);
        verify(cb, times(4)).equal(any(), eq(5L));
        verify(cb).equal(any(), eq("DELIVERY"));
        verify(cb).equal(any(), eq("LOT_SHARE"));
        verify(cb).equal(any(), eq("SALE_ALLOCATION"));
        verify(cb).equal(any(), eq("PAYMENT"));
    }

    @Test
    void lotFilterLooksInDeliveriesLossesSalesBatchesAndShares() {
        CriteriaQuery<?> query = deepQuery();

        CriteriaBuilder cb = applyFilter(new LedgerFilter(null, null, 3L, null, null, null), deepRoot(), query);

        verify(query.subquery(Long.class)).from(Delivery.class);
        verify(query.subquery(Long.class)).from(Loss.class);
        verify(query.subquery(Long.class)).from(Sale.class);
        // A sale by grade is found through its batches, and so is what it credited.
        verify(query.subquery(Long.class), times(2)).from(SaleAllocation.class);
        verify(query.subquery(Long.class)).from(LotShare.class);
        verify(cb, times(6)).equal(any(), eq(3L));
    }

    @Test
    void searchByCodeIgnoresCaseAndChecksEveryCodeAndName() {
        CriteriaBuilder cb = applyFilter(new LedgerFilter(null, null, null, null, null, " RCT-7K2Q "), deepRoot());

        // Receipt code and member name of deliveries, sale and order code, sale code and member name of credits,
        // member name of shares, payment code and member name of payments.
        verify(cb, times(9)).like(any(), eq("%rct-7k2q%"));
    }

    @Test
    void noFiltersMatchEverything() {
        CriteriaQuery<?> query = deepQuery();

        CriteriaBuilder cb = applyFilter(new LedgerFilter(null, null, null, null, null, " "), deepRoot(), query);

        verify(query, never()).subquery(any());
        verify(cb, never()).like(any(), any(String.class));
    }

    // --- Names, codes and reversal links ---

    @Test
    void entriesShowCodeMemberAndLotOfTheirRecord() {
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", 100L);
        delivery.setReceiptCode("RCT-7K2Q");
        delivery.setMember(alice);
        delivery.setLot(lotA);
        when(deliveryRepository.findByIdIn(Set.of(100L))).thenReturn(List.of(delivery));

        ProducePayment payment = new ProducePayment();
        ReflectionTestUtils.setField(payment, "id", 200L);
        payment.setPaymentCode("PAY-0001");
        payment.setMember(alice);
        when(paymentRepository.findByIdIn(Set.of(200L))).thenReturn(List.of(payment));

        Loss loss = new Loss();
        ReflectionTestUtils.setField(loss, "id", 300L);
        loss.setLot(lotA);
        when(lossRepository.findByIdIn(Set.of(300L))).thenReturn(List.of(loss));

        Sale gradeSale = sale(410L);
        gradeSale.setLot(null);
        when(saleRepository.findByIdIn(Set.of(400L, 410L))).thenReturn(List.of(sale(400L), gradeSale));

        SaleAllocation credit = new SaleAllocation(sale(400L), delivery, new BigDecimal("10.00"));
        ReflectionTestUtils.setField(credit, "id", 600L);
        when(saleAllocationRepository.findByIdIn(Set.of(600L))).thenReturn(List.of(credit));

        LotShare share = new LotShare();
        ReflectionTestUtils.setField(share, "id", 500L);
        share.setMember(alice);
        share.setLot(lotA);
        when(lotShareRepository.findByIdIn(Set.of(500L))).thenReturn(List.of(share));

        List<LedgerEntryResponse> responses = service.toResponses(List.of(
                entry(1L, LedgerEntryType.DELIVERY, "DELIVERY", 100L, "Delivery RCT-7K2Q"),
                entry(2L, LedgerEntryType.PAYMENT, "PAYMENT", 200L, "Payment PAY-0001 by CASH"),
                entry(3L, LedgerEntryType.LOSS, "LOSS", 300L, "Loss from A-2026-01: ROT"),
                entry(4L, LedgerEntryType.SALE, "SALE", 400L, "Sale SAL-9MNP from A-2026-01 to Kalisa"),
                entry(5L, LedgerEntryType.SHARE, "LOT_SHARE", 500L, "Share of A-2026-01"),
                entry(6L, LedgerEntryType.SALE, "SALE", 410L, "Sale SAL-9MNP of Big to Kalisa"),
                entry(7L, LedgerEntryType.SALE_CREDIT, "SALE_ALLOCATION", 600L, "Credit for sale SAL-9MNP")));

        assertReference(responses.get(0), "RCT-7K2Q", 5L, "Alice Uwase", 3L, "A-2026-01");
        assertReference(responses.get(1), "PAY-0001", 5L, "Alice Uwase", null, null);
        assertReference(responses.get(2), null, null, null, 3L, "A-2026-01");
        assertReference(responses.get(3), "SAL-9MNP", null, null, 3L, "A-2026-01");
        assertReference(responses.get(4), null, 5L, "Alice Uwase", 3L, "A-2026-01");
        // A sale by grade has no lot of its own.
        assertReference(responses.get(5), "SAL-9MNP", null, null, null, null);
        // What a sale credited shows the member and lot of the batch it paid for.
        assertReference(responses.get(6), "SAL-9MNP", 5L, "Alice Uwase", 3L, "A-2026-01");
        assertEquals("Kalisa Traders", responses.get(6).buyerName());
        assertEquals("Delivery RCT-7K2Q", responses.get(0).description());
    }

    @Test
    void hashIsShownShortAndInFull() {
        LedgerEntry entry = entry(1L, LedgerEntryType.LOSS, "LOSS", 300L, "Loss from A-2026-01: ROT");

        LedgerEntryResponse response = service.toResponses(List.of(entry)).get(0);

        assertEquals(entry.getCurrentHash().substring(0, 10), response.shortHash());
        assertEquals(entry.getCurrentHash(), response.hash());
        assertEquals(LedgerHasher.GENESIS_HASH, response.previousHash());
    }

    @Test
    void reversalAndOriginalPointToEachOther() {
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", 100L);
        delivery.setReceiptCode("RCT-7K2Q");
        delivery.setMember(alice);
        delivery.setLot(lotA);
        when(deliveryRepository.findByIdIn(Set.of(100L))).thenReturn(List.of(delivery));

        LedgerEntry original = entry(1L, LedgerEntryType.DELIVERY, "DELIVERY", 100L, "Delivery RCT-7K2Q");
        LedgerEntry reversal = entry(7L, LedgerEntryType.REVERSAL, "DELIVERY", 100L, "Wrong member");
        reversal.setReverses(original);
        when(ledgerEntryRepository.findByReversesIdIn(anyCollection())).thenReturn(List.of(reversal));

        List<LedgerEntryResponse> responses = service.toResponses(List.of(reversal, original));

        LedgerEntryResponse reversalResponse = responses.get(0);
        assertEquals(1L, reversalResponse.reversesEntryId());
        assertNull(reversalResponse.reversedByEntryId());
        assertEquals("Reversal of entry #1: Wrong member", reversalResponse.description());
        assertEquals("RCT-7K2Q", reversalResponse.referenceCode());

        LedgerEntryResponse originalResponse = responses.get(1);
        assertNull(originalResponse.reversesEntryId());
        assertEquals(7L, originalResponse.reversedByEntryId());
        // Both entries point to the same delivery, which is looked up once.
        verify(deliveryRepository).findByIdIn(Set.of(100L));
    }

    @Test
    void saleEntriesShowTheBuyer() {
        when(saleRepository.findByIdIn(Set.of(400L))).thenReturn(List.of(sale(400L)));
        LedgerEntry saleEntry = entry(4L, LedgerEntryType.SALE, "SALE", 400L, "Sale SAL-9MNP");
        LedgerEntry reversal = entry(8L, LedgerEntryType.REVERSAL, "SALE", 400L, "Wrong buyer");
        reversal.setReverses(saleEntry);

        List<LedgerEntryResponse> responses = service.toResponses(List.of(reversal, saleEntry));

        assertEquals("Kalisa Traders", responses.get(0).buyerName());
        assertEquals("Kalisa Traders", responses.get(1).buyerName());
    }

    @Test
    void otherEntriesHaveNoBuyer() {
        Loss loss = new Loss();
        ReflectionTestUtils.setField(loss, "id", 300L);
        loss.setLot(lotA);
        when(lossRepository.findByIdIn(Set.of(300L))).thenReturn(List.of(loss));

        LedgerEntryResponse response = service.toResponses(List.of(
                entry(3L, LedgerEntryType.LOSS, "LOSS", 300L, "Loss from A-2026-01: ROT"))).get(0);

        assertNull(response.buyerName());
    }

    @Test
    void reversalShowsTypeOfTheEntryItReverses() {
        LedgerEntry payment = entry(2L, LedgerEntryType.PAYMENT, "PAYMENT", 200L, "Payment PAY-0001 by CASH");
        LedgerEntry reversal = entry(9L, LedgerEntryType.REVERSAL, "PAYMENT", 200L, "Paid twice");
        // The reversal only holds a reference to the original, as when it is read from the database.
        LedgerEntry reference = new LedgerEntry();
        ReflectionTestUtils.setField(reference, "id", 2L);
        reversal.setReverses(reference);
        when(ledgerEntryRepository.findAllById(List.of(2L))).thenReturn(List.of(payment));

        List<LedgerEntryResponse> responses = service.toResponses(List.of(reversal, payment));

        assertEquals(LedgerEntryType.PAYMENT, responses.get(0).reversedType());
        assertNull(responses.get(1).reversedType());
        verify(ledgerEntryRepository).findAllById(List.of(2L));
    }

    @Test
    void pageWithoutReversalsDoesNotLookUpReversedEntries() {
        service.toResponses(List.of(entry(3L, LedgerEntryType.LOSS, "LOSS", 300L, "Loss")));

        verify(ledgerEntryRepository, never()).findAllById(any());
    }

    // --- Summary ---

    @Test
    void summaryCountsEntriesAndUsesTheLatestEntryDate() {
        LedgerEntry latest = entry(42L, LedgerEntryType.PAYMENT, "PAYMENT", 200L, "Payment PAY-0001 by CASH");
        when(ledgerEntryRepository.count()).thenReturn(42L);
        when(ledgerEntryRepository.findTopByOrderByIdDesc()).thenReturn(Optional.of(latest));
        LocalDate today = KigaliTime.today();
        when(ledgerEntryRepository.countBetween(today.atStartOfDay(KigaliTime.ZONE).toInstant(),
                today.plusDays(1).atStartOfDay(KigaliTime.ZONE).toInstant())).thenReturn(6L);

        LedgerSummaryResponse summary = service.summary();

        assertEquals(new LedgerSummaryResponse(42L, latest.getEntryDate(), 6L), summary);
    }

    @Test
    void emptyLedgerHasNoLastEntry() {
        when(ledgerEntryRepository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());

        LedgerSummaryResponse summary = service.summary();

        assertEquals(0L, summary.totalEntries());
        assertNull(summary.lastEntryAt());
    }

    private CriteriaBuilder applyFilter(LedgerFilter filter, Root<LedgerEntry> root) {
        return applyFilter(filter, root, deepQuery());
    }

    private CriteriaBuilder applyFilter(LedgerFilter filter, Root<LedgerEntry> root, CriteriaQuery<?> query) {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        LedgerQueryService.matching(filter).toPredicate(root, query, cb);
        return cb;
    }

    @SuppressWarnings("unchecked")
    private static Root<LedgerEntry> deepRoot() {
        return mock(Root.class, RETURNS_DEEP_STUBS);
    }

    private static CriteriaQuery<?> deepQuery() {
        return mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
    }

    private Sale sale(Long id) {
        Buyer buyer = new Buyer();
        buyer.setName("Kalisa Traders");
        Sale sale = new Sale();
        ReflectionTestUtils.setField(sale, "id", id);
        sale.setSaleCode("SAL-9MNP");
        sale.setLot(lotA);
        sale.setBuyer(buyer);
        return sale;
    }

    private static void assertReference(LedgerEntryResponse response, String code, Long memberId, String memberName,
                                        Long lotId, String lotCode) {
        assertEquals(code, response.referenceCode());
        assertEquals(memberId, response.memberId());
        assertEquals(memberName, response.memberName());
        assertEquals(lotId, response.lotId());
        assertEquals(lotCode, response.lotCode());
    }

    private static LedgerEntry entry(Long id, LedgerEntryType type, String relatedType, Long relatedId,
                                     String reason) {
        LedgerEntry entry = new LedgerEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        entry.setEntryType(type);
        entry.setRelatedEntityType(relatedType);
        entry.setRelatedEntityId(relatedId);
        entry.setQuantityKg(new BigDecimal("10.00"));
        entry.setReason(reason);
        entry.setEntryDate(Instant.parse("2026-10-13T08:00:00Z"));
        entry.setPreviousHash(LedgerHasher.GENESIS_HASH);
        entry.setCurrentHash(LedgerHasher.hash(entry));
        return entry;
    }
}
