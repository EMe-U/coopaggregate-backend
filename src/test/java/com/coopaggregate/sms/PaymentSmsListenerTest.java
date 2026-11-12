package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.coopaggregate.payment.PaymentRecordedEvent;
import com.coopaggregate.payment.PaymentReversedEvent;

class PaymentSmsListenerTest {

    private PaymentSmsService paymentSmsService;
    private SmsService smsService;
    private PaymentSmsListener listener;

    @BeforeEach
    void setUp() {
        paymentSmsService = mock(PaymentSmsService.class);
        smsService = mock(SmsService.class);
        listener = new PaymentSmsListener(paymentSmsService, smsService);
    }

    @Test
    void aRecordedPaymentsMessageIsQueuedAndSent() {
        when(paymentSmsService.queue(7L, SmsMessageType.PAYMENT)).thenReturn(Optional.of(70L));

        listener.onPaymentRecorded(new PaymentRecordedEvent(7L));

        verify(smsService).trySend(70L);
    }

    @Test
    void aReversedPaymentsMessageIsQueuedAndSent() {
        when(paymentSmsService.queue(7L, SmsMessageType.PAYMENT_REVERSED)).thenReturn(Optional.of(71L));

        listener.onPaymentReversed(new PaymentReversedEvent(7L));

        verify(smsService).trySend(71L);
    }

    @Test
    void nothingIsSentWhenNoMessageWasQueued() {
        when(paymentSmsService.queue(7L, SmsMessageType.PAYMENT)).thenReturn(Optional.empty());

        listener.onPaymentRecorded(new PaymentRecordedEvent(7L));

        verify(smsService, never()).trySend(any());
    }

    @Test
    void anSmsProblemNeverReachesThePayment() {
        when(paymentSmsService.queue(7L, SmsMessageType.PAYMENT_REVERSED))
                .thenThrow(new IllegalStateException("database is busy"));

        listener.onPaymentReversed(new PaymentReversedEvent(7L));

        verify(smsService, never()).trySend(any());
    }

    @Test
    void bothRunAfterTheCommitOnAnotherThread() throws NoSuchMethodException {
        for (Method method : new Method[] {
                PaymentSmsListener.class.getMethod("onPaymentRecorded", PaymentRecordedEvent.class),
                PaymentSmsListener.class.getMethod("onPaymentReversed", PaymentReversedEvent.class)}) {
            assertNotNull(method.getAnnotation(Async.class), method.getName());
            assertEquals(TransactionPhase.AFTER_COMMIT,
                    method.getAnnotation(TransactionalEventListener.class).phase(), method.getName());
        }
    }
}
