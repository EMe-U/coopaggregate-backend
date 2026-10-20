package com.coopaggregate.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.coopaggregate.delivery.DeliveryRecordedEvent;

/**
 * Sends the member an SMS about a new delivery. It runs only after the delivery was committed and on another
 * thread, so the delivery is never slowed down, rolled back or failed by an SMS problem.
 */
@Component
public class DeliverySmsListener {

    private static final Logger log = LoggerFactory.getLogger(DeliverySmsListener.class);

    private final SmsService smsService;

    public DeliverySmsListener(SmsService smsService) {
        this.smsService = smsService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeliveryRecorded(DeliveryRecordedEvent event) {
        try {
            smsService.queueDeliveryMessage(event.deliveryId()).ifPresent(smsService::trySend);
        } catch (RuntimeException e) {
            // The message is either still QUEUED (and retried later) or was never created; the delivery is safe.
            log.error("SMS for delivery {} could not be queued", event.deliveryId(), e);
        }
    }
}
