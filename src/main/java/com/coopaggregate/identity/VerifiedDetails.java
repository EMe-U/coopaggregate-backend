package com.coopaggregate.identity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The verified details a new member is registered with: the name and phone (+2507XXXXXXXX), always there, and the
 * birth date, gender, email, address (one line) and national ID, each null when eSignet did not release it. The national
 * ID is only asked for when MOSIP_NATIONAL_ID_CLAIM is set.
 */
public record VerifiedDetails(String fullName, String phone, LocalDate birthdate, String gender, String email,
                              String address, String nationalId) {

    public static final VerifiedDetails NONE = new VerifiedDetails(null, null);

    /** The names of the fields, as in verifiedFields, in this order. */
    public static final List<String> FIELDS = List.of("fullName", "phone", "birthdate", "gender", "email", "address",
            "nationalId");

    /** The name and phone only. */
    public VerifiedDetails(String fullName, String phone) {
        this(fullName, phone, null, null, null, null, null);
    }

    /** Without a national ID. */
    public VerifiedDetails(String fullName, String phone, LocalDate birthdate, String gender, String email,
                           String address) {
        this(fullName, phone, birthdate, gender, email, address, null);
    }

    /** The names of the fields that hold a verified value, in the order of FIELDS. */
    public List<String> verifiedFields() {
        List<String> fields = new ArrayList<>();
        Object[] values = {fullName, phone, birthdate, gender, email, address, nationalId};
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
