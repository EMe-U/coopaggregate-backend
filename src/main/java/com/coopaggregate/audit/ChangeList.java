package com.coopaggregate.audit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Collects "field: old -> new" for the fields that really changed, for the change history. */
public class ChangeList {

    private final List<String> changes = new ArrayList<>();

    public ChangeList add(String field, Object oldValue, Object newValue) {
        if (!same(oldValue, newValue)) {
            changes.add(field + ": " + show(oldValue) + " -> " + show(newValue));
        }
        return this;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    @Override
    public String toString() {
        return String.join("; ", changes);
    }

    // 100 and 100.00 kg are the same value.
    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static String show(Object value) {
        if (value == null || value.toString().isEmpty()) {
            return "(empty)";
        }
        if (value instanceof BigDecimal number) {
            return number.stripTrailingZeros().toPlainString();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "(empty)" : String.join(", ", list.stream().map(String::valueOf).toList());
        }
        return value.toString();
    }
}
