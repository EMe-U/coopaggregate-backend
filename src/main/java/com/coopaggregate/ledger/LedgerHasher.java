package com.coopaggregate.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * Each hash includes the previous entry's hash, so changing any past entry changes
 * every hash after it and the edit is detected when the chain is verified.
 */
public final class LedgerHasher {

    public static final String GENESIS_HASH = "0".repeat(64);

    private LedgerHasher() {
    }

    public static String hash(LedgerEntry entry) {
        String content = String.join("|",
                text(entry.getPreviousHash()),
                format(entry.getEntryDate()),
                entry.getEntryType() == null ? "" : entry.getEntryType().name(),
                text(entry.getRelatedEntityType()),
                text(entry.getRelatedEntityId()),
                format(entry.getQuantityKg()),
                text(entry.getAmount()),
                text(entry.getReason()),
                entry.getReverses() == null ? "" : text(entry.getReverses().getId()),
                entry.getManager() == null ? "" : text(entry.getManager().getId()));

        return sha256Hex(content);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    // Postgres stores timestamps in microseconds, so anything finer would be lost on save.
    private static String format(Instant value) {
        return value == null ? "" : value.truncatedTo(ChronoUnit.MICROS).toString();
    }

    // numeric(12,2) in the database, so 12.5 and 12.50 must hash the same.
    private static String format(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
