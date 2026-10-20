package com.surprising.realtime.api;

import java.time.ZoneOffset;

/** Portfolio income uses one exchange business day (UTC+08:00), independent of a device clock. */
public final class PortfolioIncomePeriod {
    public static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
    public static final long DAY_MILLIS = 86_400_000;
    public static final long MINUTE_MILLIS = 60_000;
    public static final long MAXIMUM_MARK_AGE_MILLIS = 120_000;
    private static final long OFFSET_MILLIS = 28_800_000;
    private PortfolioIncomePeriod() {}

    public static long dayStart(long epochMillis) {
        return Math.subtractExact(Math.multiplyExact(Math.floorDiv(Math.addExact(epochMillis, OFFSET_MILLIS),
                DAY_MILLIS), DAY_MILLIS), OFFSET_MILLIS);
    }

    public static long minuteStart(long epochMillis) {
        return Math.multiplyExact(Math.floorDiv(epochMillis, MINUTE_MILLIS), MINUTE_MILLIS);
    }
}
