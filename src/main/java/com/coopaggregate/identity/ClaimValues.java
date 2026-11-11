package com.coopaggregate.identity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the optional details of eSignet's userinfo answer, as measured on the real eSignet. A value that does not fit
 * is left out (the login still succeeds); nothing here logs or keeps a value. Control characters are removed from all
 * text.
 */
final class ClaimValues {

    static final int MAX_GENDER = 20;
    static final int MAX_EMAIL = 254;
    static final int MAX_ADDRESS = 500;
    static final int MAX_NATIONAL_ID = 20;
    static final LocalDate EARLIEST_BIRTHDATE = LocalDate.of(1900, 1, 1);
    // The parts of an address object, in the order they are joined. Any other key (postal_code, formatted) is ignored.
    static final List<String> ADDRESS_PARTS = List.of("street_address", "locality", "region", "country");

    // eSignet sends yyyy/MM/dd; yyyy-MM-dd (the OIDC format) is accepted too. STRICT refuses 2001/02/30.
    private static final DateTimeFormatter SLASHES =
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DASHES =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private ClaimValues() {
    }

    /** Text without control characters, spaces trimmed and runs of spaces made one; null when nothing is left. */
    static String text(Object value) {
        if (!(value instanceof String raw)) {
            return null;
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** The birth date, or null when it is not a real date, is after today or before 1900. */
    static LocalDate birthdate(Object value, LocalDate today) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        LocalDate date = parse(text, SLASHES);
        if (date == null) {
            date = parse(text, DASHES);
        }
        if (date == null || date.isAfter(today) || date.isBefore(EARLIEST_BIRTHDATE)) {
            return null;
        }
        return date;
    }

    /** The gender as given ("Female"), or null when it is longer than 20 characters. */
    static String gender(Object value) {
        String text = text(value);
        return text == null || text.length() > MAX_GENDER ? null : text;
    }

    /** The email, or null when it has no @ or is longer than 254 characters. */
    static String email(Object value) {
        String text = text(value);
        return text == null || text.length() > MAX_EMAIL || !text.contains("@") ? null : text;
    }

    /**
     * The national ID as released (eSignet's UIN in the local setup): text or a whole number, spaces removed, then only
     * letters and digits, at most 20 (the size of member.national_id). null when it does not fit. Not checked against
     * the 16 digits a typed ID needs: the identity provider is the source.
     */
    static String nationalId(Object value) {
        String text = value instanceof Long || value instanceof Integer || value instanceof java.math.BigInteger
                ? value.toString() : text(value);
        if (text == null) {
            return null;
        }
        String id = text.replaceAll("\\s+", "");
        return id.matches("[A-Za-z0-9]{1," + MAX_NATIONAL_ID + "}") ? id : null;
    }

    /**
     * The address on one line: an object's street_address, locality, region and country (those present, in that
     * order, joined with ", "), or a plain string as given. Cut cleanly at 500 characters.
     */
    static String address(Object value) {
        String line;
        if (value instanceof Map<?, ?> parts) {
            List<String> present = new ArrayList<>();
            for (String key : ADDRESS_PARTS) {
                String part = text(parts.get(key));
                if (part != null) {
                    present.add(part);
                }
            }
            line = present.isEmpty() ? null : String.join(", ", present);
        } else {
            line = text(value);
        }
        return line == null ? null : cut(line, MAX_ADDRESS);
    }

    // At most max characters, ending on a whole word when there is a space or comma to cut at, never half of a
    // surrogate pair, and without a dangling ", " or space at the end.
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        String cut = text.substring(0, end);
        if (!isBreak(text.charAt(end))) {
            int lastBreak = Math.max(cut.lastIndexOf(' '), cut.lastIndexOf(','));
            if (lastBreak > 0) {
                cut = cut.substring(0, lastBreak);
            }
        }
        cut = cut.replaceAll("[\\s,]+$", "");
        return cut.isEmpty() ? null : cut;
    }

    private static boolean isBreak(char c) {
        return c == ' ' || c == ',';
    }

    private static LocalDate parse(String text, DateTimeFormatter format) {
        try {
            return LocalDate.parse(text, format);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
