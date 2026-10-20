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

    public SmsService(SmsLogRepository smsLogRepository, DeliveryRepository deliveryRepository,
                      SettingRepository settingRepository, SmsSender smsSender) {
        this.smsLogRepository = smsLogRepository;
        this.deliveryRepository = deliveryRepository;
        this.settingRepository = settingRepository;
        this.smsSender = smsSender;
    }

    /** Saves the delivery message as QUEUED. Empty when SMS are turned off or the message already exists. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> queueDeliveryMessage(Long deliveryId) {
        boolean smsEnabled = settingRepository.findById(1L).map(setting -> setting.isSmsEnabled()).orElse(false);
        if (!smsEnabled || smsLogRepository.existsByDeliveryId(deliveryId)) {
            return Optional.empty();
        }
        Delivery delivery = deliveryRepository.findById(deliveryId).orElseThrow();
        Member member = delivery.getMember();

        SmsLog sms = new SmsLog();
        sms.setMember(member);
        sms.setDelivery(delivery);
        sms.setPhone(member.getPhone());
        sms.setMessage(SmsMessages.delivery(member.getPreferredLanguage(), delivery.getConfirmationStatus(),
                delivery.getReceiptCode(), delivery.getQuantityKg(), delivery.getLot().getGrade().getName(),
                delivery.getDeliveredAt()));
        return Optional.of(smsLogRepository.save(sms).getId());
    }

    /** Sends one queued or failed message and records the result. Never throws. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void trySend(Long smsLogId) {
        SmsLog sms = smsLogRepository.findById(smsLogId).orElseThrow();
        if (sms.getStatus() == SmsStatus.SENT) {
            return;
        }
        try {
            smsSender.send(sms.getPhone(), sms.getMessage());
            sms.markSent(smsSender.name(), Instant.now());
        } catch (RuntimeException e) {
            log.warn("SMS {} could not be sent (attempt {}): {}", sms.getId(), sms.getAttempts() + 1, e.getMessage());
            sms.markFailed(smsSender.name(), e.getMessage());
        }
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
