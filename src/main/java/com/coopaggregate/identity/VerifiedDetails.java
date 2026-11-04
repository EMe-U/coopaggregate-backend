package com.coopaggregate.identity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The verified details a new member is registered with: the name and phone (+2507XXXXXXXX), always there, and the
 * birth date, gender, email and address (one line), each null when eSignet did not release it.
 */
public record VerifiedDetails(String fullName, String phone, LocalDate birthdate, String gender, String email,
                              String address) {

    public static final VerifiedDetails NONE = new VerifiedDetails(null, null);

    /** The names of the fields, as in verifiedFields, in this order. */
    public static final List<String> FIELDS = List.of("fullName", "phone", "birthdate", "gender", "email", "address");

    /** The name and phone only. */
    public VerifiedDetails(String fullName, String phone) {
        this(fullName, phone, null, null, null, null);
    }

    /** The names of the fields that hold a verified value, in the order of FIELDS. */
    public List<String> verifiedFields() {
        List<String> fields = new ArrayList<>();
        Object[] values = {fullName, phone, birthdate, gender, email, address};
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                fields.add(FIELDS.get(i));
            }
        }
        return List.copyOf(fields);
    }

    // Records print their fields; the details must never end up in a log line.
    @Override
    public String toString() {
        return "VerifiedDetails[****]";
    }
}
