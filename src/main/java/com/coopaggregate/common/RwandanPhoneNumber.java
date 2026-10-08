package com.coopaggregate.common;

import java.util.regex.Pattern;

public final class RwandanPhoneNumber {

    // Mobile numbers start with 072 or 073 (Airtel) or 078 or 079 (MTN).
    // Accepted as 07XXXXXXXX, 2507XXXXXXXX or +2507XXXXXXXX.
    public static final String PATTERN = "^(\\+250|250|0)7[2389]\\d{7}$";

    private static final Pattern COMPILED_PATTERN = Pattern.compile(PATTERN);

    private RwandanPhoneNumber() {
    }

    public static boolean isValid(String phone) {
        return phone != null && COMPILED_PATTERN.matcher(phone).matches();
    }

    /** Returns the number as +2507XXXXXXXX, the format stored in the database. */
    public static String normalize(String phone) {
        if (!isValid(phone)) {
            throw new IllegalArgumentException("Not a Rwandan mobile number: " + phone);
        }
        String lastNineDigits = phone.substring(phone.length() - 9);
        return "+250" + lastNineDigits;
    }
}
