package com.coopaggregate.sms;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.delivery.ConfirmationMethod;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.DisputeReason;

/**
 * Handles members' SMS replies: "1 RCT-7K2Q" confirms a delivery, "2 RCT-7K2Q <note>" disputes it. A reply only
 * counts when it comes from the phone of the member who owns that delivery; anything else is ignored.
 */
@Service
public class InboundSmsService {

    private static final Logger log = LoggerFactory.getLogger(InboundSmsService.class);
    private static final Pattern REPLY = Pattern.compile("^([12])\\s+(RCT-[A-Z0-9]{4})(?:\\s+(.*))?$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int MAX_NOTE_LENGTH = 500;

    private final DeliveryRepository deliveryRepository;

    public InboundSmsService(DeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    public enum Result {
        CONFIRMED,
        DISPUTED,
        IGNORED
    }

    @Transactional
    public Result handle(String fromPhone, String text) {
        Matcher reply = REPLY.matcher(text == null ? "" : text.trim());
        if (!reply.matches()) {
            return Result.IGNORED;
        }
        String receiptCode = reply.group(2).toUpperCase(Locale.ROOT);
        Delivery delivery = deliveryRepository.findByReceiptCode(receiptCode).orElse(null);
        if (delivery == null || delivery.isReversed() || !sentByOwner(delivery, fromPhone)) {
            log.info("Ignored SMS reply about {}", receiptCode);
            return Result.IGNORED;
        }

        if (reply.group(1).equals("1")) {
            if (delivery.getConfirmationStatus() != ConfirmationStatus.PENDING) {
                return Result.IGNORED;
            }
            delivery.confirm(ConfirmationMethod.SMS, Instant.now());
            return Result.CONFIRMED;
        }

        // "2" also works on a confirmed delivery: the member may say a confirmation was not theirs.
        if (delivery.getConfirmationStatus() == ConfirmationStatus.DISPUTED) {
            return Result.IGNORED;
        }
        delivery.dispute(DisputeReason.OTHER, note(reply.group(3)), Instant.now());
        return Result.DISPUTED;
    }

    private static boolean sentByOwner(Delivery delivery, String fromPhone) {
        if (fromPhone == null) {
            return false;
        }
        String phone = fromPhone.trim();
        if (RwandanPhoneNumber.isValid(phone)) {
            phone = RwandanPhoneNumber.normalize(phone);
        }
        return phone.equals(delivery.getMember().getPhone());
    }

    private static String note(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String note = text.trim();
        return note.length() > MAX_NOTE_LENGTH ? note.substring(0, MAX_NOTE_LENGTH) : note;
    }
}
