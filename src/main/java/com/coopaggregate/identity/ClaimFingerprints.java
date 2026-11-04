package com.coopaggregate.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.coopaggregate.common.RwandanPhoneNumber;

/**
 * Brings the details of a person into one form so the provider's version can be compared with the one typed on the
 * member form, and hashes them with a per-verification salt so the provider's claims never have to be stored. All
 * rules are exact after normalising: nothing is fuzzy.
 */
final class ClaimFingerprints {

    static final String NAME = "name";
    static final String BIRTH = "birth";
    static final String PHONE = "phone";

    private static final SecureRandom RANDOM = new SecureRandom();
    // MOSIP usually writes the birth date as 1990/05/17; OpenID Connect says 1990-05-17. Both are read.
    private static final DateTimeFormatter SLASHES = DateTimeFormatter.ofPattern("uuuu/MM/dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private ClaimFingerprints() {
    }

    /** Without accents, lower case, punctuation as spaces, words in alphabetical order. Null when nothing is left. */
    static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (plain.isEmpty()) {
            return null;
        }
        String[] words = plain.split(" ");
        Arrays.sort(words);
        return String.join(" ", words);
    }

    /** ISO date (1990-05-17) from yyyy/MM/dd or yyyy-MM-dd. Null when it is neither. */
    static String normalizeBirth(String birthdate) {
        if (birthdate == null || birthdate.isBlank()) {
            return null;
        }
        String text = birthdate.trim();
        try {
            return (text.contains("/") ? LocalDate.parse(text, SLASHES) : LocalDate.parse(text)).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String normalizeBirth(LocalDate birthDate) {
        return birthDate == null ? null : birthDate.toString();
    }

    /** +2507XXXXXXXX. Null when it is not a Rwandan mobile number. */
    static String normalizePhone(String phone) {
        if (phone == null || !RwandanPhoneNumber.isValid(phone.trim())) {
            return null;
        }
        return RwandanPhoneNumber.normalize(phone.trim());
    }

    static String newSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** HMAC-SHA256 of one normalised detail, keyed with the salt. */
    static String hash(String salt, String kind, String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((kind + ":" + normalized).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** Compared in constant time. */
    static boolean same(String hashA, String hashB) {
        return hashA != null && hashB != null
                && MessageDigest.isEqual(hashA.getBytes(StandardCharsets.UTF_8), hashB.getBytes(StandardCharsets.UTF_8));
    }
}
