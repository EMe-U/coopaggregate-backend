package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.member.Member;
import com.coopaggregate.payment.PaymentMethod;
import com.coopaggregate.payment.PaymentService;
import com.coopaggregate.payment.ProducePayment;
import com.coopaggregate.payment.ProducePaymentRepository;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

class PaymentSmsServiceTest {

    private SmsLogRepository smsLogRepository;
    private PaymentService paymentService;
    private Setting setting;
    private Member member;
    private ProducePayment payment;
    private PaymentSmsService service;

    @BeforeEach
    void setUp() {
        smsLogRepository = mock(SmsLogRepository.class);
        ProducePaymentRepository paymentRepository = mock(ProducePaymentRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        paymentService = mock(PaymentService.class);

        setting = new Setting();
        setting.setSmsEnabled(true);
        setting.setCooperativeName("Koperative Intagamburuzwa");
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));

        member = new Member();
        ReflectionTestUtils.setField(member, "id", 5L);
        member.setFullName("Uwimana Claudine");
        member.setMemberCode("M-0005");
        member.setPhone("+250788123456");
        member.setPreferredLanguage("en");

        payment = new ProducePayment();
        ReflectionTestUtils.setField(payment, "id", 7L);
        payment.setPaymentCode("PAY-0007");
        payment.setMember(member);
        payment.setAmount(25_000L);
        payment.setMethod(PaymentMethod.MOMO);
        payment.setReferenceNo("MP2611120001");
        payment.setPaymentDate(LocalDate.of(2026, 11, 12));
        when(paymentRepository.findById(7L)).thenReturn(Optional.of(payment));
        when(paymentService.owedTo(5L)).thenReturn(10_000L);
        when(smsLogRepository.save(any(SmsLog.class))).thenAnswer(invocation -> {
            SmsLog sms = invocation.getArgument(0);
            ReflectionTestUtils.setField(sms, "id", 70L);
            return sms;
        });

        service = new PaymentSmsService(smsLogRepository, paymentRepository, settingRepository, paymentService);
    }

    @Test
    void aPaymentMessageIsQueuedWithTheAmountAndTheBalanceAfterIt() {
        assertEquals(Optional.of(70L), service.queue(7L, SmsMessageType.PAYMENT));

        SmsLog sms = saved();
        assertEquals(SmsMessageType.PAYMENT, sms.getMessageType());
        assertEquals(payment, sms.getPayment());
        assertNull(sms.getDelivery());
        assertEquals(member, sms.getMember());
        assertEquals("+250788123456", sms.getPhone());
        assertEquals(SmsStatus.QUEUED, sms.getStatus());
        assertEquals("Koperative Intagamburuzwa: payment PAY-0007 of 25,000 RWF (MoMo) on 12/11. You are now owed "
                + "10,000 RWF. If this is wrong, contact the cooperative manager.", sms.getMessage());
    }

    @Test
    void aReversalMessageIsQueuedWithTheReasonAndTheNewBalance() {
        reverse("Paid twice");
        when(paymentService.owedTo(5L)).thenReturn(35_000L);

        assertEquals(Optional.of(70L), service.queue(7L, SmsMessageType.PAYMENT_REVERSED));

        SmsLog sms = saved();
        assertEquals(SmsMessageType.PAYMENT_REVERSED, sms.getMessageType());
        assertEquals("Koperative Intagamburuzwa: payment PAY-0007 of 25,000 RWF was cancelled. Reason: Paid twice. "
                + "You are now owed 35,000 RWF. If this is wrong, contact the cooperative manager.", sms.getMessage());
    }

    @Test
    void theMessageIsInTheMembersLanguage() {
        member.setPreferredLanguage("rw");

        service.queue(7L, SmsMessageType.PAYMENT);

        assertTrue(saved().getMessage().contains(": wishyuwe 25,000 RWF (PAY-0007, MoMo) ku 12/11."));
    }

    @Test
    void noNameAndNoOtherMemberDataIsInTheMessage() {
        service.queue(7L, SmsMessageType.PAYMENT);

        String text = saved().getMessage();
        assertFalse(text.contains("Uwimana"));
        assertFalse(text.contains("Claudine"));
        assertFalse(text.contains("M-0005"));
        assertFalse(text.contains("788123456"));
        assertFalse(text.contains("MP2611120001"), "not the transaction reference either");
    }

    @Test
    void nothingIsQueuedWhileSmsAreTurnedOff() {
        setting.setSmsEnabled(false);

        assertEquals(Optional.empty(), service.queue(7L, SmsMessageType.PAYMENT));
        reverse("Paid twice");
        assertEquals(Optional.empty(), service.queue(7L, SmsMessageType.PAYMENT_REVERSED));
        verify(smsLogRepository, never()).save(any());
    }

    @Test
    void aMessageIsNeverQueuedTwiceForTheSamePaymentAndKind() {
        when(smsLogRepository.existsByPaymentIdAndMessageType(7L, SmsMessageType.PAYMENT)).thenReturn(true);

        assertEquals(Optional.empty(), service.queue(7L, SmsMessageType.PAYMENT));
        verify(smsLogRepository, never()).save(any());
    }

    @Test
    void aPaymentAlreadyReversedGetsNoPaymentMessage() {
        reverse("Wrong member");

        assertEquals(Optional.empty(), service.queue(7L, SmsMessageType.PAYMENT));
        verify(smsLogRepository, never()).save(any());
    }

    @Test
    void aPaymentThatIsNotReversedGetsNoReversalMessage() {
        assertEquals(Optional.empty(), service.queue(7L, SmsMessageType.PAYMENT_REVERSED));
        verify(smsLogRepository, never()).save(any());
    }

    @Test
    void onlyPaymentMessagesAreQueuedHere() {
        assertThrows(IllegalArgumentException.class, () -> service.queue(7L, SmsMessageType.RECEIPT));
        assertThrows(IllegalArgumentException.class, () -> service.queue(7L, SmsMessageType.DELIVERY_CODE));
    }

    private void reverse(String reason) {
        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryDate(Instant.parse("2026-11-12T10:00:00Z"));
        reversal.setReason(reason);
        payment.markReversed(reversal);
    }

    private SmsLog saved() {
        ArgumentCaptor<SmsLog> sms = ArgumentCaptor.forClass(SmsLog.class);
        verify(smsLogRepository).save(sms.capture());
        return sms.getValue();
    }
}
