package com.coopaggregate.sms;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.delivery.ConfirmationCodes;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.member.Member;
import com.coopaggregate.setting.SettingRepository;

/**
 * Queues and sends SMS to members. Every message is saved in sms_log first, so a message that fails is retried
 * later (SmsRetryJob) and never lost. Each step runs in its own transaction, so callers must go through the
 * Spring bean, not call these methods on this class itself.
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    static final int MAX_ATTEMPTS = 5;
    // A message still QUEUED after this long was never sent, for example because the app stopped.
    private static final Duration STUCK_AFTER = Duration.ofMinutes(10);

    private final SmsLogRepository smsLogRepository;
    private final DeliveryRepository deliveryRepository;
    private final SettingRepository settingRepository;
    private final SmsSender smsSender;
    private final ConfirmationCodes confirmationCodes;

    public SmsService(SmsLogRepository smsLogRepository, DeliveryRepository deliveryRepository,
                      SettingRepository settingRepository, SmsSender smsSender,
                      ConfirmationCodes confirmationCodes) {
        this.smsLogRepository = smsLogRepository;
        this.deliveryRepository = deliveryRepository;
        this.settingRepository = settingRepository;
        this.smsSender = smsSender;
        this.confirmationCodes = confirmationCodes;
    }

    /**
     * Saves a delivery's code message as QUEUED, with the code masked. sendNumber is 0 for the first message and
     * 1 to 3 for resends. Empty when SMS are turned off or this message already exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> queueCodeMessage(Long deliveryId, int sendNumber) {
        boolean smsEnabled = settingRepository.findById(1L).map(setting -> setting.isSmsEnabled()).orElse(false);
        if (!smsEnabled || smsLogRepository.existsByDeliveryIdAndSendNumber(deliveryId, sendNumber)) {
            return Optional.empty();
        }
        Delivery delivery = deliveryRepository.findById(deliveryId).orElseThrow();
        Member member = delivery.getMember();

        SmsLog sms = new SmsLog();
        sms.setMember(member);
        sms.setDelivery(delivery);
        sms.setSendNumber(sendNumber);
        sms.setPhone(member.getPhone());
        sms.setMessage(SmsMessages.deliveryCode(member.getPreferredLanguage(), delivery.getReceiptCode(),
                delivery.getQuantityKg(), delivery.getLot().getGrade().getName(), delivery.getDeliveredAt(),
                SmsMessages.CODE_MASK));
        return Optional.of(smsLogRepository.save(sms).getId());
    }

    /**
     * Sends one queued or failed message and records the result. Never throws. A code message gets a new code
     * on every attempt: only its hash is stored, so a code from an earlier attempt could not be sent again, and
     * the member never received it anyway.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void trySend(Long smsLogId) {
        SmsLog sms = smsLogRepository.findById(smsLogId).orElseThrow();
        if (sms.getStatus() == SmsStatus.SENT || sms.getStatus() == SmsStatus.CANCELLED) {
            return;
        }
        String text = sms.getMessage();
        String code = null;
        Delivery delivery = null;
        if (sms.getDelivery() != null && text.contains(SmsMessages.CODE_MASK)) {
            delivery = deliveryRepository.findForUpdateById(sms.getDelivery().getId()).orElseThrow();
            String reason = reasonNotToSend(sms, delivery);
            if (reason != null) {
                sms.cancel(reason);
                return;
            }
            code = confirmationCodes.issue(delivery, Instant.now());
            text = text.replace(SmsMessages.CODE_MASK, code);
        }
        try {
            smsSender.send(sms.getPhone(), text);
            sms.markSent(smsSender.name(), Instant.now());
        } catch (RuntimeException e) {
            String error = code == null || e.getMessage() == null
                    ? e.getMessage()
                    : e.getMessage().replace(code, SmsMessages.CODE_MASK);
            log.warn("SMS {} could not be sent (attempt {}): {}", sms.getId(), sms.getAttempts() + 1, error);
            sms.markFailed(smsSender.name(), error);
            if (delivery != null) {
                // The member never got this code, so it must not work either.
                delivery.clearCode();
            }
        }
    }

    // A code is only worth sending while the delivery waits for one and no newer code was asked for.
    private String reasonNotToSend(SmsLog sms, Delivery delivery) {
        if (delivery.isReversed()) {
            return "The delivery was reversed.";
        }
        if (delivery.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            return "The delivery is already " + delivery.getConfirmationStatus() + ".";
        }
        if (smsLogRepository.existsByDeliveryIdAndSendNumberGreaterThan(delivery.getId(), sms.getSendNumber())) {
            return "A newer code was requested.";
        }
        return null;
    }

    /** Messages to send again: FAILED ones with attempts left, and QUEUED ones that were never sent. */
    @Transactional(readOnly = true)
    public List<Long> findRetryableIds() {
        List<Long> ids = new ArrayList<>();
        smsLogRepository.findTop50ByStatusAndAttemptsLessThanOrderByIdAsc(SmsStatus.FAILED, MAX_ATTEMPTS)
                .forEach(sms -> ids.add(sms.getId()));
        smsLogRepository.findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(SmsStatus.QUEUED,
                        Instant.now().minus(STUCK_AFTER))
                .forEach(sms -> ids.add(sms.getId()));
        return ids;
    }
}
