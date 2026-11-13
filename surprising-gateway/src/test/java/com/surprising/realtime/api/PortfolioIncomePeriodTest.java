package com.surprising.realtime.api;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PortfolioIncomePeriodTest {
    @Test void exchangeMidnightAndMinuteBoundariesDoNotDependOnTheDeviceZone() {
        long before = Instant.parse("2026-10-20T15:59:59.999Z").toEpochMilli();
        long midnight = Instant.parse("2026-10-20T16:00:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-10-19T16:00:00Z").toEpochMilli(), PortfolioIncomePeriod.dayStart(before));
        assertEquals(midnight, PortfolioIncomePeriod.dayStart(midnight));
        assertEquals(Instant.parse("2026-10-20T15:59:00Z").toEpochMilli(), PortfolioIncomePeriod.minuteStart(before));
        assertEquals(midnight, PortfolioIncomePeriod.minuteStart(midnight));
    }

    @Test void yearRolloverUsesTheSameExplicitBusinessDay() {
        long instant = Instant.parse("2026-12-31T16:01:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-12-31T16:00:00Z").toEpochMilli(), PortfolioIncomePeriod.dayStart(instant));
        assertEquals("+08:00", PortfolioIncomePeriod.ZONE.getId());
    }
}
