package com.coopaggregate.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.ledger.LedgerEntryRepository;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

import jakarta.persistence.EntityNotFoundException;

class PaymentServiceTest {

    private static final UUID CLIENT_UUID = UUID.fromString("3f2b7c1e-9a4d-4e8b-b1c2-5d6e7f8a9b0c");

    private ProducePaymentRepository paymentRepository;
    private MemberRepository memberRepository;
    private LedgerService ledgerService;
    private LedgerEntryRepository ledgerEntryRepository;
    private Member alice;
    private Manager manager;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(ProducePaymentRepository.class);
        memberRepository = mock(MemberRepository.class);
        ledgerService = mock(LedgerService.class);

        alice = member(1L, "Alice Uwase", "M-0001", "+250788000001");
        when(memberRepository.findForUpdateById(1L)).thenReturn(Optional.of(alice));
        when(paymentRepository.findByClientUuid(any())).thenReturn(Optional.empty());
        // Shares minus deductions 1,451,000; already paid 1,000,000; owed 451,000.
        when(paymentRepository.netSharesOf(1L)).thenReturn(1_451_000L);
        when(paymentRepository.paidTo(1L)).thenReturn(1_000_000L);
        when(paymentRepository.highestPaymentNumber()).thenReturn(4);
        when(paymentRepository.save(any(ProducePayment.class))).thenAnswer(invocation -> {
            ProducePayment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", 9L);
            return payment;
        });

        manager = new Manager();
        ledgerEntryRepository = mock(LedgerEntryRepository.class);
        service = new PaymentService(paymentRepository, memberRepository, ledgerService, ledgerEntryRepository);
    }

    @Test
    void owedIsSharesMinusDeductionsMinusPayments() {
        assertEquals(451_000L, service.owedTo(1L));
    }

    @Test
    void paymentUpToOwedIsRecorded() {
        RecordedPayment result = pay(451_000L, PaymentMethod.CASH, null);

        assertTrue(result.created());
        assertEquals(451_000L, result.payment().amountRwf());
        assertEquals("M-0001", result.payment().memberNumber());
        assertEquals(KigaliTime.today(), result.payment().paidAt());
    }

    @Test
    void overpaymentIsRejected() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> pay(451_001L, PaymentMethod.CASH, null));

        assertEquals("Member M-0001 is owed 451000 RWF. The payment cannot be more than that.", e.getMessage());
        verify(paymentRepository, never()).save(any());
        verify(paymentRepository, never()).highestPaymentNumber();
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = {"MOMO", "BANK"})
    void momoAndBankPaymentsNeedReference(PaymentMethod method) {
        BadRequestException e = assertThrows(BadRequestException.class, () -> pay(100_000L, method, " "));

        assertEquals("A " + method + " payment needs the transaction reference.", e.getMessage());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void paymentGetsNextPayCode() {
        assertEquals("PAY-0005", pay(100_000L, PaymentMethod.CASH, null).payment().paymentCode());
    }

    @Test
    void firstPaymentGetsPayCodeOne() {
        when(paymentRepository.highestPaymentNumber()).thenReturn(0);

        assertEquals("PAY-0001", pay(100_000L, PaymentMethod.CASH, null).payment().paymentCode());
    }

    @Test
    void cashPaymentNeedsNoReference() {
        assertTrue(pay(100_000L, PaymentMethod.CASH, null).created());
    }

    @Test
    void bankPaymentWithReferenceIsRecorded() {
        RecordedPayment result = pay(100_000L, PaymentMethod.BANK, "BK-778812");

        assertEquals(PaymentMethod.BANK, result.payment().method());
        assertEquals("BK-778812", result.payment().reference());
    }

    @Test
    void unknownMemberIsRejected() {
        when(memberRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> service.record(
                new PaymentRequest(CLIENT_UUID, 99L, 1_000L, PaymentMethod.CASH, null, null), manager));
    }

    @Test
    void sameClientUuidReturnsExistingPayment() {
        ProducePayment existing = new ProducePayment();
        ReflectionTestUtils.setField(existing, "id", 4L);
        existing.setMember(alice);
        existing.setAmount(200_000L);
        existing.setMethod(PaymentMethod.CASH);
        when(paymentRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(existing));

        RecordedPayment result = pay(999_999_999L, PaymentMethod.CASH, null);

        assertFalse(result.created());
        assertEquals(4L, result.payment().id());
        verify(paymentRepository, never()).save(any());
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void paymentIsRecordedInLedger() {
        pay(100_000L, PaymentMethod.MOMO, " MP2610120001 ");

        verify(ledgerService).record(LedgerEntryType.PAYMENT, "PAYMENT", 9L, null, 100_000L,
                "Payment PAY-0005 by MOMO MP2610120001", manager);
    }

    @Test
    void balancesShowStatusPerMemberLargestBalanceFirst() {
        givenAliceBoscoCarine();

        BalancesResponse balances = service.balances(null, null, LocalDate.of(2026, 10, 14));

        assertEquals(List.of(
                new BalancesResponse.MemberBalance(2L, "Bosco Habimana", "M-0002", "+250788000002",
                        885_600L, 15_000L, 0L, 870_600L, BalanceStatus.UNPAID),
                new BalancesResponse.MemberBalance(1L, "Alice Uwase", "M-0001", "+250788000001",
                        1_476_000L, 25_000L, 1_000_000L, 451_000L, BalanceStatus.PART_PAID),
                new BalancesResponse.MemberBalance(3L, "Carine Mukamana", "M-0003", "+250788000003",
                        590_400L, 10_000L, 580_400L, 0L, BalanceStatus.PAID)), balances.members());
        assertEquals(1_321_600L, balances.totalOwedRwf());
    }

    @Test
    void onlyMembersWithASharedLotAreListed() {
        givenAliceBoscoCarine();

        service.balances(null, null, LocalDate.of(2026, 10, 14));

        verify(memberRepository).findAllById(Set.of(1L, 2L, 3L));
    }

    @Test
    void paidThisWeekRunsFromMondayToSundayInKigali() {
        givenAliceBoscoCarine();
        when(paymentRepository.paidBetween(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)))
                .thenReturn(200_000L);

        assertEquals(200_000L, service.balances(null, null, LocalDate.of(2026, 10, 14)).paidThisWeekRwf());
        assertEquals(200_000L, service.balances(null, null, LocalDate.of(2026, 10, 12)).paidThisWeekRwf());
        assertEquals(200_000L, service.balances(null, null, LocalDate.of(2026, 10, 18)).paidThisWeekRwf());
    }

    @Test
    void statusFilterKeepsTotalsForAllMembers() {
        givenAliceBoscoCarine();

        BalancesResponse paid = service.balances(null, BalanceStatus.PAID, LocalDate.of(2026, 10, 14));

        assertEquals(1, paid.members().size());
        assertEquals("M-0003", paid.members().get(0).memberNumber());
        assertEquals(1_321_600L, paid.totalOwedRwf());
    }

    @Test
    void balancesCanBeSearchedByPhone() {
        givenAliceBoscoCarine();

        BalancesResponse balances = service.balances("0788000002", null, LocalDate.of(2026, 10, 14));

        assertEquals(1, balances.members().size());
        assertEquals("Bosco Habimana", balances.members().get(0).memberName());
    }

    private void givenAliceBoscoCarine() {
        Member bosco = member(2L, "Bosco Habimana", "M-0002", "+250788000002");
        Member carine = member(3L, "Carine Mukamana", "M-0003", "+250788000003");
        when(paymentRepository.findShareTotalsByMember()).thenReturn(List.of(
                new MemberShareTotals(1L, 1_476_000L, 25_000L),
                new MemberShareTotals(2L, 885_600L, 15_000L),
                new MemberShareTotals(3L, 590_400L, 10_000L)));
        when(paymentRepository.findPaymentsByMember()).thenReturn(List.of(
                new MemberAmount(1L, 1_000_000L), new MemberAmount(3L, 580_400L)));
        when(memberRepository.findAllById(any())).thenReturn(List.of(alice, bosco, carine));
    }

    // --- Reversing a payment ---

    @Test
    void reversingAPaymentMarksItAndAddsALedgerReversal() {
        givenStoredPayment(200_000L);
        LedgerEntry reversal = reversalEntry("Paid twice");
        when(ledgerService.reverse(55L, "Paid twice", manager)).thenReturn(reversal);

        ProducePayment reversed = service.reverse(9L, "Paid twice", manager);

        assertTrue(reversed.isReversed());
        assertEquals("Paid twice", reversed.getReversalReason());
        assertEquals(reversal, reversed.getReversalLedgerEntry());
        assertTrue(PaymentResponse.from(reversed).reversed());
        verify(memberRepository).findForUpdateById(1L);
    }

    @Test
    void reversedPaymentIsOwedAgain() {
        ProducePayment payment = givenStoredPayment(200_000L);
        // Behaves like the paidTo query: reversed payments are left out.
        when(paymentRepository.paidTo(1L)).thenAnswer(invocation -> payment.isReversed() ? 0L : 200_000L);
        when(paymentRepository.netSharesOf(1L)).thenReturn(1_451_000L);
        when(ledgerService.reverse(55L, "Paid twice", manager)).thenReturn(reversalEntry("Paid twice"));

        assertEquals(1_251_000L, service.owedTo(1L));
        service.reverse(9L, "Paid twice", manager);

        assertEquals(1_451_000L, service.owedTo(1L));
    }

    @Test
    void paymentCannotBeReversedTwice() {
        ProducePayment payment = givenStoredPayment(200_000L);
        payment.markReversed(reversalEntry("First time"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(9L, "Again", manager));

        assertEquals("Payment PAY-0009 has already been reversed.", e.getMessage());
        verify(ledgerService, never()).reverse(any(), any(), any());
    }

    @Test
    void reversingAnUnknownPaymentIsNotFound() {
        when(paymentRepository.findMemberIdById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.reverse(99L, "Unknown", manager));
    }

    private ProducePayment givenStoredPayment(long amount) {
        ProducePayment payment = new ProducePayment();
        ReflectionTestUtils.setField(payment, "id", 9L);
        payment.setPaymentCode("PAY-0009");
        payment.setMember(alice);
        payment.setAmount(amount);
        payment.setMethod(PaymentMethod.CASH);
        when(paymentRepository.findMemberIdById(9L)).thenReturn(Optional.of(1L));
        when(paymentRepository.findById(9L)).thenReturn(Optional.of(payment));
        LedgerEntry entry = new LedgerEntry();
        ReflectionTestUtils.setField(entry, "id", 55L);
        when(ledgerEntryRepository.findFirstByEntryTypeAndRelatedEntityTypeAndRelatedEntityId(
                LedgerEntryType.PAYMENT, "PAYMENT", 9L)).thenReturn(Optional.of(entry));
        return payment;
    }

    private static LedgerEntry reversalEntry(String reason) {
        LedgerEntry reversal = new LedgerEntry();
        ReflectionTestUtils.setField(reversal, "id", 91L);
        reversal.setEntryType(LedgerEntryType.REVERSAL);
        reversal.setEntryDate(Instant.parse("2026-10-15T09:00:00Z"));
        reversal.setReason(reason);
        return reversal;
    }

    private RecordedPayment pay(long amount, PaymentMethod method, String reference) {
        return service.record(new PaymentRequest(CLIENT_UUID, 1L, amount, method, reference, null), manager);
    }

    private static Member member(Long id, String name, String code, String phone) {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", id);
        member.setFullName(name);
        member.setMemberCode(code);
        member.setPhone(phone);
        return member;
    }
}
