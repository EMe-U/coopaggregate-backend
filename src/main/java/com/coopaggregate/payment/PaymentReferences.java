package com.coopaggregate.payment;

import java.util.Locale;
import java.util.regex.Pattern;

import com.coopaggregate.error.BadRequestException;

/**
 * The rules for MoMo and bank transaction references, for farmer payments and buyer payments alike. A MOMO or BANK
 * reference is required, trimmed, 6 to 40 characters of letters, digits, dot, dash or slash, and used only once per
 * method (compared ignoring case). A CASH reference stays optional and free.
 */
public final class PaymentReferences {

    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 40;
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9./-]+");

    // Namespaces for pg_advisory_xact_lock(namespace, hashtext(key)): the two-number form cannot meet the ledger's
    // one-number lock.
    public static final int FARMER_PAYMENT_LOCK = 2026111301;
    public static final int BUYER_PAYMENT_LOCK = 2026111302;

    private PaymentReferences() {
    }

    /** The trimmed reference, or null when blank. missingMessage is used when MOMO or BANK has none. */
    public static String checked(PaymentMethod method, String reference, String missingMessage) {
        String trimmed = reference == null || reference.isBlank() ? null : reference.trim();
        if (!mustBeUnique(method)) {
            return trimmed;
        }
        if (trimmed == null) {
            throw new BadRequestException(missingMessage);
        }
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH || !ALLOWED.matcher(trimmed).matches()) {
            throw new BadRequestException("The " + method + " reference must be " + MIN_LENGTH + " to " + MAX_LENGTH
                    + " characters: letters, digits, dot (.), dash (-) or slash (/) only.");
        }
        return trimmed;
    }

    /** MOMO and BANK references name one transaction, so each can be used once. */
    public static boolean mustBeUnique(PaymentMethod method) {
        return method != PaymentMethod.CASH;
    }

    /** What two requests using the same reference both lock, so only one of them can pass the check. */
    public static String lockKey(PaymentMethod method, String reference) {
        return method + ":" + reference.toLowerCase(Locale.ROOT);
    }

    /** Answered with 409. */
    public static IllegalStateException alreadyUsed(String reference, String code) {
        return new IllegalStateException("Reference " + reference + " was already used for " + code + ".");
    }
}
