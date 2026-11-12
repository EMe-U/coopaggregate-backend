package com.coopaggregate.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.coopaggregate.payment.PaymentRecordedEvent;
import com.coopaggregate.payment.PaymentReversedEvent;

/**
 * Sends the member an SMS when a payment to them is recorded or reversed. It runs only after the payment was
 * committed and on another thread, so the payment is never slowed down, rolled back or failed by an SMS problem.
 */
@Component
public class PaymentSmsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentSmsListener.class);

    private final PaymentSmsService paymentSmsService;
    private final SmsService smsService;

    public PaymentSmsListener(PaymentSmsService paymentSmsService, SmsService smsService) {
        this.paymentSmsService = paymentSmsService;
        this.smsService = smsService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRecorded(PaymentRecordedEvent event) {
        send(event.paymentId(), SmsMessageType.PAYMENT);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentReversed(PaymentReversedEvent event) {
        send(event.paymentId(), SmsMessageType.PAYMENT_REVERSED);
    }

    private void send(Long paymentId, SmsMessageType type) {
        try {
            paymentSmsService.queue(paymentId, type).ifPresent(smsService::trySend);
        } catch (RuntimeException e) {
            // The message is either still QUEUED (and retried later) or was never created; the payment is safe.
            log.error("{} SMS for payment {} could not be queued", type, paymentId, e);
        }
    }
}
