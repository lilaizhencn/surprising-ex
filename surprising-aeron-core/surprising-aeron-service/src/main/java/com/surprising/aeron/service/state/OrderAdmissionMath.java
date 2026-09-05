package com.surprising.aeron.service.state;

final class OrderAdmissionMath {

    private OrderAdmissionMath() {
    }

    static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    static long fragmentationSafeFeeDebit(CoreInstrumentState instrument, ResolvedPlaceOrder order) {
        long feePerStep = CoreContractMath.feeDeltaUnits(
                instrument, order.reservationPriceTicks(), 1, order.takerFeeRatePpm());
        long debitPerStep = Math.max(0, Math.negateExact(feePerStep));
        return Math.multiplyExact(debitPerStep, order.quantitySteps());
    }
}
