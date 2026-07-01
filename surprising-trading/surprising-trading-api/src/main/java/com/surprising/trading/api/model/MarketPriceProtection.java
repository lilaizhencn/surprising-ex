package com.surprising.trading.api.model;

public final class MarketPriceProtection {

    public static final long PPM = 1_000_000L;

    private MarketPriceProtection() {
    }

    public static long protectedPriceTicks(OrderSide side, long markPriceTicks, long maxSlippagePpm) {
        if (markPriceTicks <= 0) {
            throw new IllegalArgumentException("markPriceTicks must be positive");
        }
        long boundedSlippage = Math.max(0L, Math.min(PPM - 1L, maxSlippagePpm));
        DivisionResult slippage = multiplyDivide(markPriceTicks, boundedSlippage, PPM);
        if (side == OrderSide.BUY) {
            long roundedUpSlippage = slippage.hasRemainder()
                    ? Math.addExact(slippage.value(), 1L)
                    : slippage.value();
            return Math.addExact(markPriceTicks, roundedUpSlippage);
        }
        return Math.max(1L, Math.subtractExact(markPriceTicks, slippage.value()));
    }

    private static DivisionResult multiplyDivide(long left, long right, long divisor) {
        long quotient = left / divisor;
        long remainder = left % divisor;
        long head = Math.multiplyExact(quotient, right);
        long tailProduct = Math.multiplyExact(remainder, right);
        long tail = tailProduct / divisor;
        return new DivisionResult(Math.addExact(head, tail), tailProduct % divisor != 0);
    }

    private record DivisionResult(long value, boolean hasRemainder) {
    }
}
