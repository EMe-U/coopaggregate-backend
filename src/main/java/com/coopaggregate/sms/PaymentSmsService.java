package com.coopaggregate.sms;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.member.Member;
import com.coopaggregate.payment.PaymentService;
import com.coopaggregate.payment.ProducePayment;
import com.coopaggregate.payment.ProducePaymentRepository;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

/**
 * Queues the SMS a member gets about a payment: PAYMENT when it is recorded, PAYMENT_REVERSED when it is reversed.
 * Called after the payment's transaction committed, so the balance in the message already counts it. Sending and
 * retrying are the same as for every other SMS (SmsService).
 */
@Service
public class PaymentSmsService {

    private final SmsLogRepository smsLogRepository;
    private final ProducePaymentRepository paymentRepository;
    private final SettingRepository settingRepository;
    private final PaymentService paymentService;

    public PaymentSmsService(SmsLogRepository smsLogRepository, ProducePaymentRepository paymentRepository,
                             SettingRepository settingRepository, PaymentService paymentService) {
        this.smsLogRepository = smsLogRepository;
        this.paymentRepository = paymentRepository;
        this.settingRepository = settingRepository;
        this.paymentService = paymentService;
    }

    /**
     * Saves the message as QUEUED. Empty when SMS are turned off, this message already exists (one per payment and
     * kind, backed by a unique index), or it would no longer be true: a payment already reversed gets no "payment"
     * message, and a payment that is not reversed gets no "reversed" one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> queue(Long paymentId, SmsMessageType type) {
        if (type != SmsMessageType.PAYMENT && type != SmsMessageType.PAYMENT_REVERSED) {
            throw new IllegalArgumentException(type + " is not a payment message.");
        }
        Setting setting = settingRepository.findById(1L).orElse(null);
        if (setting == null || !setting.isSmsEnabled()
                || smsLogRepository.existsByPaymentIdAndMessageType(paymentId, type)) {
            return Optional.empty();
        }
        ProducePayment payment = paymentRepository.findById(paymentId).orElseThrow();
        boolean reversedMessage = type == SmsMessageType.PAYMENT_REVERSED;
        if (payment.isReversed() != reversedMessage) {
            return Optional.empty();
        }
        Member member = payment.getMember();
        long owed = paymentService.owedTo(member.getId());

        SmsLog sms = new SmsLog();
        sms.setMember(member);
        sms.setPayment(payment);
        sms.setMessageType(type);
        sms.setPhone(member.getPhone());
        sms.setMessage(reversedMessage
                ? SmsMessages.paymentReversed(member.getPreferredLanguage(), setting.getCooperativeName(),
                        payment.getPaymentCode(), payment.getAmount(), payment.getReversalReason(), owed)
                : SmsMessages.payment(member.getPreferredLanguage(), setting.getCooperativeName(),
                        payment.getPaymentCode(), payment.getAmount(), payment.getMethod().name(),
                        payment.getPaymentDate(), owed));
        return Optional.of(smsLogRepository.save(sms).getId());
    }
}
