package com.surprising.aeron.service.state;

/** Migration-only guard that compares the mutable Runtime with the authoritative core state. */
public final class RuntimeStateParityChecker {

    private RuntimeStateParityChecker() {
    }

    public static void assertMatches(TradingCoreState expected, RuntimeIdentityRegistry identities,
                                     TradingRuntimeState actual) {
        if (expected == null || identities == null || actual == null) {
            throw new IllegalArgumentException("parity arguments are required");
        }
        TradingCoreState materialized = RuntimeStateMaterializer.materialize(actual, identities);
        if (!expected.equals(materialized) || expected.businessStateHash() != materialized.businessStateHash()) {
            TradingRuntimeState projected = RuntimeStateProjector.project(expected, identities);
            TradingRuntimeSnapshot expectedSnapshot = projected.snapshot(expected.revision());
            TradingRuntimeSnapshot actualSnapshot = actual.snapshot(expected.revision());
            throw new IllegalStateException("runtime parity mismatch at revision " + expected.revision()
                    + ": " + mismatch(expectedSnapshot, actualSnapshot)
                    + " hashes=" + expected.businessStateHash() + '/' + materialized.businessStateHash());
        }
    }

    private static String mismatch(TradingRuntimeSnapshot expected, TradingRuntimeSnapshot actual) {
        if (!expected.users().equals(actual.users())) return "users";
        if (!expected.balances().equals(actual.balances())) return "balances";
        if (!expected.orders().equals(actual.orders())) return "orders";
        if (!expected.reservations().equals(actual.reservations())) return "reservations";
        if (!expected.clientOrderIndex().equals(actual.clientOrderIndex())) return "client-order-index";
        if (!expected.positions().equals(actual.positions())) return "positions";
        if (!expected.liquidations().equals(actual.liquidations())) return mapMismatch(
                "liquidations", expected.liquidations(), actual.liquidations());
        if (!expected.markPrices().equals(actual.markPrices())) return "mark-prices";
        if (!expected.riskSnapshots().equals(actual.riskSnapshots())) return mapMismatch(
                "risk-snapshots", expected.riskSnapshots(), actual.riskSnapshots());
        if (!expected.riskScans().equals(actual.riskScans())) return mapMismatch(
                "risk-scans", expected.riskScans(), actual.riskScans());
        if (expected.nextLiquidationId() != actual.nextLiquidationId()) return "next-liquidation-id";
        if (!expected.instruments().equals(actual.instruments())) return "instruments";
        if (!expected.leverages().equals(actual.leverages())) return "leverages";
        if (!expected.algoOrders().equals(actual.algoOrders())) return "algo-orders";
        if (!expected.cancelAllAfterTimers().equals(actual.cancelAllAfterTimers())) return "cancel-all-after";
        if (!expected.triggerOrders().equals(actual.triggerOrders())) return "trigger-orders";
        if (!expected.treasury().equals(actual.treasury())) return "treasury";
        if (!expected.fundingSettlements().equals(actual.fundingSettlements())) return "funding-settlements";
        return "funding-progress";
    }

    private static String mapMismatch(String name, java.util.Map<?, ?> expected, java.util.Map<?, ?> actual) {
        for (java.util.Map.Entry<?, ?> entry : expected.entrySet()) {
            Object actualValue = actual.get(entry.getKey());
            if (!java.util.Objects.equals(entry.getValue(), actualValue)) {
                return name + " sizes=" + expected.size() + '/' + actual.size() + " key=" + entry.getKey()
                        + " expected=" + entry.getValue() + " actual=" + actualValue;
            }
        }
        for (java.util.Map.Entry<?, ?> entry : actual.entrySet()) {
            if (!expected.containsKey(entry.getKey())) {
                return name + " sizes=" + expected.size() + '/' + actual.size() + " unexpected=" + entry;
            }
        }
        return name + " sizes=" + expected.size() + '/' + actual.size();
    }
}
