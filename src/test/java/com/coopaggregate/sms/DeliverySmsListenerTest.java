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

import com.coopaggregate.delivery.ConfirmationCodeResentEvent;
import com.coopaggregate.delivery.DeliveryConfirmedEvent;
import com.coopaggregate.delivery.DeliveryRecordedEvent;

class DeliverySmsListenerTest {

    private SmsService smsService;
    private DeliverySmsListener listener;

    @BeforeEach
    void setUp() {
        smsService = mock(SmsService.class);
        listener = new DeliverySmsListener(smsService);
    }

    @Test
    void queuedMessageIsSent() {
        when(smsService.queueCodeMessage(42L, 0)).thenReturn(Optional.of(7L));

        listener.onDeliveryRecorded(new DeliveryRecordedEvent(42L));

        verify(smsService).trySend(7L);
    }

    @Test
    void nothingIsSentWhenNoMessageWasQueued() {
        when(smsService.queueCodeMessage(42L, 0)).thenReturn(Optional.empty());

        listener.onDeliveryRecorded(new DeliveryRecordedEvent(42L));

        verify(smsService, never()).trySend(any());
    }

    @Test
    void smsFailureNeverReachesTheDelivery() {
        when(smsService.queueCodeMessage(42L, 0)).thenThrow(new IllegalStateException("database is busy"));

        listener.onDeliveryRecorded(new DeliveryRecordedEvent(42L));
    }

    @Test
    void resentCodeIsQueuedWithItsSendNumberAndSent() {
        when(smsService.queueCodeMessage(42L, 2)).thenReturn(Optional.of(8L));

        listener.onCodeResent(new ConfirmationCodeResentEvent(42L, 2));

        verify(smsService).trySend(8L);
    }

    @Test
    void aConfirmedDeliveryGetsItsReceipt() {
        when(smsService.queueReceipt(42L)).thenReturn(Optional.of(9L));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(42L));

        verify(smsService).trySend(9L);
    }

    @Test
    void noReceiptIsSentWhenNoneWasQueued() {
        when(smsService.queueReceipt(42L)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(42L));

        verify(smsService, never()).trySend(any());
    }

    @Test
    void aReceiptThatFailsNeverReachesTheConfirmation() {
        when(smsService.queueReceipt(42L)).thenThrow(new IllegalStateException("database is busy"));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(42L));
    }

    @Test
    void theReceiptIsOnlySentAfterTheConfirmationIsCommittedAndOnAnotherThread() throws Exception {
        Method method = DeliverySmsListener.class.getMethod("onDeliveryConfirmed", DeliveryConfirmedEvent.class);

        assertEquals(TransactionPhase.AFTER_COMMIT, method.getAnnotation(TransactionalEventListener.class).phase());
        assertNotNull(method.getAnnotation(Async.class));
    }

    @Test
    void resentCodeIsOnlySentAfterCommitAndOnAnotherThread() throws Exception {
        Method method = DeliverySmsListener.class.getMethod("onCodeResent", ConfirmationCodeResentEvent.class);

        assertEquals(TransactionPhase.AFTER_COMMIT, method.getAnnotation(TransactionalEventListener.class).phase());
        assertNotNull(method.getAnnotation(Async.class));
    }

    @Test
    void smsIsOnlySentAfterTheDeliveryIsCommittedAndOnAnotherThread() throws Exception {
        Method method = DeliverySmsListener.class.getMethod("onDeliveryRecorded", DeliveryRecordedEvent.class);

        assertEquals(TransactionPhase.AFTER_COMMIT, method.getAnnotation(TransactionalEventListener.class).phase());
        assertNotNull(method.getAnnotation(Async.class));
    }
}
