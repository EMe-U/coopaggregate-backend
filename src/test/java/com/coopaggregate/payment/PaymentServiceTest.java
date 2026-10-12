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
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

class PaymentServiceTest {

    private static final UUID CLIENT_UUID = UUID.fromString("3f2b7c1e-9a4d-4e8b-b1c2-5d6e7f8a9b0c");

    private ProducePaymentRepository paymentRepository;
    private MemberRepository memberRepository;
    private LedgerService ledgerService;
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
        when(paymentRepository.save(any(ProducePayment.class))).thenAnswer(invocation -> {
            ProducePayment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", 9L);
            return payment;
        });

        manager = new Manager();
        service = new PaymentService(paymentRepository, memberRepository, ledgerService);
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
                "Payment by MOMO MP2610120001", manager);
    }

    @Test
    void owedListKeepsOnlyMembersStillOwedLargestFirst() {
        Member bosco = member(2L, "Bosco Habimana", "M-0002", "+250788000002");
        Member carine = member(3L, "Carine Mukamana", "M-0003", "+250788000003");
        when(paymentRepository.findNetSharesByMember()).thenReturn(List.of(
                new MemberAmount(1L, 1_451_000L), new MemberAmount(2L, 870_600L), new MemberAmount(3L, 580_400L)));
        when(paymentRepository.findPaymentsByMember()).thenReturn(List.of(
                new MemberAmount(1L, 1_000_000L), new MemberAmount(3L, 580_400L)));
        when(memberRepository.findAllById(any())).thenReturn(List.of(alice, bosco));

        OwedMembersResponse owed = service.owedMembers(null);

        assertEquals(List.of(
                new OwedMembersResponse.OwedMember(2L, "Bosco Habimana", "M-0002", "+250788000002", 870_600L),
                new OwedMembersResponse.OwedMember(1L, "Alice Uwase", "M-0001", "+250788000001", 451_000L)),
                owed.members());
        assertEquals(1_321_600L, owed.totalOwedRwf());
        verify(memberRepository).findAllById(Set.of(1L, 2L));
        assertFalse(owed.members().stream().anyMatch(member -> member.memberId().equals(carine.getId())));
    }

    @Test
    void owedListCanBeSearchedByPhone() {
        Member bosco = member(2L, "Bosco Habimana", "M-0002", "+250788000002");
        when(paymentRepository.findNetSharesByMember()).thenReturn(List.of(
                new MemberAmount(1L, 1_451_000L), new MemberAmount(2L, 870_600L)));
        when(paymentRepository.findPaymentsByMember()).thenReturn(List.of());
        when(memberRepository.findAllById(any())).thenReturn(List.of(alice, bosco));

        OwedMembersResponse owed = service.owedMembers("0788000002");

        assertEquals(1, owed.members().size());
        assertEquals(870_600L, owed.totalOwedRwf());
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
