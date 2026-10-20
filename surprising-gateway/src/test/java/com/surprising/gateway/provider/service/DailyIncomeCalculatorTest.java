package com.surprising.gateway.provider.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyIncomeCalculatorTest {
    private static final Instant START = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private static BigDecimal amount(String value) { return new BigDecimal(value); }
    private static DailyIncomeCalculator.ExternalFlow flow(String id, long seconds, String value) {
        return new DailyIncomeCalculator.ExternalFlow(id, START.plusSeconds(seconds), amount(value));
    }

    @Test void aDepositAloneDoesNotCreateProfit() {
        var income = DailyIncomeCalculator.calculate(START, END, amount("100"), amount("150"),
                List.of(flow("deposit", 1800, "50")));
        assertEquals(0, income.amount().signum());
        assertEquals(0, income.percent().signum());
        assertEquals(amount("50"), income.netExternalFlows());
    }

    @Test void withdrawalsAreRemovedAndFeesRemainLosses() {
        var income = DailyIncomeCalculator.calculate(START, END, amount("100"), amount("59.5"),
                List.of(flow("withdrawal", 1800, "-40")));
        assertEquals(amount("-0.5"), income.amount());
        assertEquals(0, amount("-0.625").compareTo(income.percent()));
    }

    @Test void gainsIncludeMarketValueAndUseTimeWeightedCapital() {
        var income = DailyIncomeCalculator.calculate(START, END, amount("100"), amount("175"),
                List.of(flow("deposit", 1800, "50")));
        assertEquals(amount("25"), income.amount());
        assertEquals(0, amount("20").compareTo(income.percent()));
    }

    @Test void valuesBeyondDoublePrecisionAndEighteenDecimalsStayExact() {
        var income = DailyIncomeCalculator.calculate(START, END, amount("9007199254740993.000000000000000001"),
                amount("9007199254740994.000000000000000003"), List.of(flow("deposit", 0, "1")));
        assertEquals(amount("0.000000000000000002"), income.amount());
    }

    @Test void emptyAndNegativeCapitalHaveNoInventedPercentage() {
        assertNull(DailyIncomeCalculator.calculate(START, END, BigDecimal.ZERO, amount("10"), List.of()).percent());
        assertNull(DailyIncomeCalculator.calculate(START, END, amount("-10"), amount("-9"), List.of()).percent());
    }

    @Test void fundsAtTheOpeningBoundaryCountButClosingFlowsHaveNoTimeWeight() {
        var income = DailyIncomeCalculator.calculate(START, END, amount("100"), amount("180"),
                List.of(flow("at-open", 0, "50"), flow("at-close", 3600, "15")));
        assertEquals(amount("15"), income.amount());
        assertEquals(0, amount("10").compareTo(income.percent()));
    }

    @Test void partialOrDuplicateInputCannotBeSilentlyTreatedAsZero() {
        assertThrows(NullPointerException.class, () -> DailyIncomeCalculator.calculate(START, END, null, amount("1"), List.of()));
        assertThrows(NullPointerException.class, () -> DailyIncomeCalculator.calculate(START, END, amount("1"), amount("1"), null));
        assertThrows(IllegalArgumentException.class, () -> DailyIncomeCalculator.calculate(START, END, amount("1"), amount("1"),
                List.of(flow("duplicate", 1, "1"), flow("duplicate", 2, "1"))));
    }

    @Test void foreignPeriodAndReversedClockAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DailyIncomeCalculator.calculate(START, START, amount("1"), amount("1"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> DailyIncomeCalculator.calculate(END, START, amount("1"), amount("1"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> DailyIncomeCalculator.calculate(START, END, amount("1"), amount("1"), List.of(flow("old", -1, "1"))));
        assertThrows(IllegalArgumentException.class, () -> DailyIncomeCalculator.calculate(START, END, amount("1"), amount("1"), List.of(flow("future", 3601, "1"))));
    }
}
