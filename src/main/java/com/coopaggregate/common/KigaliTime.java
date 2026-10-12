package com.coopaggregate.common;

import java.time.LocalDate;
import java.time.ZoneId;

// The cooperative works in Rwanda, so "today" and "this month" follow Kigali time,
// not the server's time zone.
public final class KigaliTime {

    public static final ZoneId ZONE = ZoneId.of("Africa/Kigali");

    private KigaliTime() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
