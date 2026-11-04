package com.coopaggregate.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.batch.SaleAllocationRepository;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.member.MemberRepository;
import com.coopaggregate.sale.Sale;
import com.coopaggregate.share.LotShare;
import com.coopaggregate.share.LotShareRepository;
import com.coopaggregate.share.MemberLotKg;

import jakarta.persistence.EntityNotFoundException;

class StatementServiceTest {

    private MemberRepository memberRepository;
    private LotShareRepository lotShareRepository;
    private LotRepository lotRepository;
    private ProducePaymentRepository paymentRepository;
    private SaleAllocationRepository saleAllocationRepository;
    private StatementService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        lotShareRepository = mock(LotShareRepository.class);
        lotRepository = mock(LotRepository.class);
        paymentRepository = mock(ProducePaymentRepository.class);
        saleAllocationRepository = mock(SaleAllocationRepository.class);
        service = new StatementService(memberRepository, lotShareRepository, lotRepository, paymentRepository,
                saleAllocationRepository);
        when(memberRepository.existsById(1L)).thenReturn(true);
    }

    @Test
    void statementAddsUpSharesDeductionsAndPayments() {
        Lot shared = new Lot();
        ReflectionTestUtils.setField(shared, "id", 3L);
        LotShare share = new LotShare();
        share.setLot(shared);
        share.setMemberKg(new BigDecimal("5000.00"));
        share.setLotKg(new BigDecimal("10000.00"));
        share.setShareAmount(1_476_000L);
        share.setDeductionAmount(25_000L);
        when(lotShareRepository.findByMemberId(1L)).thenReturn(List.of(share));
        when(lotShareRepository.findLotsOfMember(1L)).thenReturn(List.of(
                new MemberLotKg(5L, "A-2026-02", LotStatus.OPEN, new BigDecimal("300.00")),
                new MemberLotKg(3L, "A-2026-01", LotStatus.SHARED, new BigDecimal("5000.00"))));
        when(lotRepository.findQuantities(anyCollection())).thenReturn(List.of(
                new LotQuantities(5L, new BigDecimal("1200.00"), null, null, null, null),
                new LotQuantities(3L, new BigDecimal("10000.00"), null, new BigDecimal("10000.00"), 2_952_000L, 3L)));
        when(paymentRepository.findByMemberIdAndReversedAtIsNullOrderByPaymentDateDescIdDesc(1L)).thenReturn(List.of(
                payment("PAY-0002", LocalDate.of(2026, 10, 12), 200_000L, PaymentMethod.MOMO, "MP2610120001"),
                payment("PAY-0001", LocalDate.of(2026, 10, 10), 1_000_000L, PaymentMethod.CASH, null)));

        StatementResponse statement = service.statement(1L);

        assertEquals(1_476_000L, statement.grossShareRwf());
        assertEquals(25_000L, statement.deductionsRwf());
        assertEquals(1_200_000L, statement.paidRwf());
        assertEquals(251_000L, statement.owedRwf());

        StatementResponse.LotShareLine waiting = statement.lotShares().get(0);
        assertEquals("A-2026-02", waiting.lotCode());
        assertEquals(LotStatus.OPEN, waiting.status());
        assertEquals(new BigDecimal("1200.00"), waiting.lotKg());
        assertEquals(new BigDecimal("25.00"), waiting.sharePercent());
        assertNull(waiting.shareRwf());

        assertEquals(new StatementResponse.LotShareLine(3L, "A-2026-01", LotStatus.SHARED, new BigDecimal("5000.00"),
                new BigDecimal("10000.00"), new BigDecimal("50.00"), 1_476_000L), statement.lotShares().get(1));
        assertEquals(new StatementResponse.PaymentLine("PAY-0002", LocalDate.of(2026, 10, 12), 200_000L,
                PaymentMethod.MOMO, "MP2610120001"), statement.payments().get(0));
    }

    @Test
    void saleCreditsByBatchAreListedAndAddedToTheShares() {
        Lot shared = new Lot();
        ReflectionTestUtils.setField(shared, "id", 3L);
        LotShare share = new LotShare();
        share.setLot(shared);
        share.setMemberKg(new BigDecimal("5000.00"));
        share.setLotKg(new BigDecimal("10000.00"));
        share.setShareAmount(1_476_000L);
        share.setDeductionAmount(25_000L);
        when(lotShareRepository.findByMemberId(1L)).thenReturn(List.of(share));
        when(lotShareRepository.findLotsOfMember(1L)).thenReturn(List.of());
        when(saleAllocationRepository.findCreditsOfMember(1L)).thenReturn(List.of(
                credit("SAL-7K2Q", "RCT-AB12", "A-2026-03", "100.00", 300L, 30_000L, 500L)));
        when(paymentRepository.findByMemberIdAndReversedAtIsNullOrderByPaymentDateDescIdDesc(1L)).thenReturn(List.of(
                payment("PAY-0001", LocalDate.of(2026, 10, 10), 1_000_000L, PaymentMethod.CASH, null)));

        StatementResponse statement = service.statement(1L);

        assertEquals(List.of(new StatementResponse.SaleCreditLine("SAL-7K2Q", LocalDate.of(2026, 10, 28), "RCT-AB12",
                "A-2026-03", new BigDecimal("100.00"), 300L, 30_000L, 500L, 29_500L)), statement.saleCredits());
        assertEquals(1_506_000L, statement.grossShareRwf());
        assertEquals(25_500L, statement.deductionsRwf());
        assertEquals(480_500L, statement.owedRwf());
    }

    @Test
    void memberWithoutActivityHasEmptyStatement() {
        when(lotShareRepository.findByMemberId(1L)).thenReturn(List.of());
        when(lotShareRepository.findLotsOfMember(1L)).thenReturn(List.of());
        when(paymentRepository.findByMemberIdAndReversedAtIsNullOrderByPaymentDateDescIdDesc(1L)).thenReturn(List.of());

        StatementResponse statement = service.statement(1L);

        assertEquals(0L, statement.owedRwf());
        assertEquals(List.of(), statement.lotShares());
        assertEquals(List.of(), statement.saleCredits());
    }

    @Test
    void unknownMemberIsNotFound() {
        EntityNotFoundException e = assertThrows(EntityNotFoundException.class, () -> service.statement(99L));

        assertEquals("Member 99 does not exist.", e.getMessage());
    }

    private static SaleAllocation credit(String saleCode, String receiptCode, String lotCode, String kg,
                                         long pricePerKg, long gross, long deduction) {
        Sale sale = new Sale();
        sale.setSaleCode(saleCode);
        sale.setSaleDate(LocalDate.of(2026, 10, 28));
        sale.setPricePerKg(pricePerKg);
        Lot lot = new Lot();
        lot.setLotCode(lotCode);
        Delivery batch = new Delivery();
        batch.setReceiptCode(receiptCode);
        batch.setLot(lot);
        SaleAllocation credit = new SaleAllocation(sale, batch, new BigDecimal(kg));
        ReflectionTestUtils.setField(credit, "grossRwf", gross);
        ReflectionTestUtils.setField(credit, "deductionRwf", deduction);
        return credit;
    }

    private static ProducePayment payment(String code, LocalDate date, long amount, PaymentMethod method,
                                          String reference) {
        ProducePayment payment = new ProducePayment();
        payment.setPaymentCode(code);
        payment.setPaymentDate(date);
        payment.setAmount(amount);
        payment.setMethod(method);
        payment.setReferenceNo(reference);
        return payment;
    }
}
