package com.surprising.account.provider.service;

import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.PositionChange;
import com.surprising.account.provider.model.PositionState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.OrderSide;
import java.math.BigInteger;
import org.springframework.stereotype.Component;

@Component
public class PositionCalculator {

    public PositionChange apply(PositionState current,
                                OrderSide side,
                                long priceTicks,
                                long quantitySteps,
                                ContractSpec positionSpec,
                                ContractSpec fillSpec) {
        if (priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("priceTicks and quantitySteps must be positive");
        }
        if (positionSpec == null || fillSpec == null) {
            throw new IllegalArgumentException("contract specs are required");
        }
        long signedDelta = side == OrderSide.BUY ? quantitySteps : -quantitySteps;
        if (current.signedQuantitySteps() == 0) {
            return new PositionChange(new PositionState(signedDelta, fillSpec.version(), priceTicks,
                    current.realizedPnlUnits()), 0L);
        }

        long currentQty = current.signedQuantitySteps();
        boolean sameDirection = (currentQty > 0 && signedDelta > 0) || (currentQty < 0 && signedDelta < 0);
        if (sameDirection) {
            if (current.instrumentVersion() != fillSpec.version()) {
                throw new IllegalStateException("cannot merge fills from different instrument versions");
            }
            long oldAbs = Math.absExact(currentQty);
            long addAbs = Math.absExact(signedDelta);
            long weightedPrice = averageEntryPriceTicks(positionSpec.contractType(), oldAbs,
                    current.entryPriceTicks(), addAbs, priceTicks);
            return new PositionChange(new PositionState(Math.addExact(currentQty, signedDelta), current.instrumentVersion(),
                    weightedPrice,
                    current.realizedPnlUnits()), 0L);
        }

        long closeQty = Math.min(Math.absExact(currentQty), Math.absExact(signedDelta));
        long pnlDelta = realizedPnlUnits(currentQty, current.entryPriceTicks(), priceTicks, closeQty, positionSpec);
        long newQty = Math.addExact(currentQty, signedDelta);
        long newRealizedPnl = Math.addExact(current.realizedPnlUnits(), pnlDelta);
        long newEntryPrice = newQty == 0 ? 0L
                : Long.signum(newQty) == Long.signum(currentQty) ? current.entryPriceTicks() : priceTicks;
        long newInstrumentVersion = newQty == 0 ? 0L
                : Long.signum(newQty) == Long.signum(currentQty) ? current.instrumentVersion() : fillSpec.version();
        return new PositionChange(new PositionState(newQty, newInstrumentVersion, newEntryPrice, newRealizedPnl),
                pnlDelta);
    }

    private long averageEntryPriceTicks(ContractType contractType,
                                        long oldAbs,
                                        long oldEntryPriceTicks,
                                        long addAbs,
                                        long addPriceTicks) {
        if (contractType == ContractType.LINEAR_PERPETUAL) {
            return Math.addExact(
                    Math.multiplyExact(oldAbs, oldEntryPriceTicks),
                    Math.multiplyExact(addAbs, addPriceTicks)) / Math.addExact(oldAbs, addAbs);
        }
        // Inverse contracts average entry by value, which is a harmonic average of entry prices.
        BigInteger numerator = big(Math.addExact(oldAbs, addAbs))
                .multiply(big(oldEntryPriceTicks))
                .multiply(big(addPriceTicks));
        BigInteger denominator = big(oldAbs).multiply(big(addPriceTicks))
                .add(big(addAbs).multiply(big(oldEntryPriceTicks)));
        return Math.max(1L, toLongRounded(numerator, denominator));
    }

    private long realizedPnlUnits(long currentSignedQty,
                                  long entryPriceTicks,
                                  long exitPriceTicks,
                                  long closeQty,
                                  ContractSpec contractSpec) {
        if (contractSpec.contractType() == ContractType.LINEAR_PERPETUAL) {
            long priceDiff = currentSignedQty > 0
                    ? Math.subtractExact(exitPriceTicks, entryPriceTicks)
                    : Math.subtractExact(entryPriceTicks, exitPriceTicks);
            return Math.multiplyExact(Math.multiplyExact(priceDiff, closeQty),
                    contractSpec.notionalMultiplierUnits());
        }
        BigInteger signedQuantity = big(closeQty).multiply(BigInteger.valueOf(currentSignedQty > 0 ? 1L : -1L));
        BigInteger numerator = signedQuantity
                .multiply(big(contractSpec.notionalMultiplierUnits()))
                .multiply(big(contractSpec.settleScaleUnits()))
                .multiply(big(Math.subtractExact(exitPriceTicks, entryPriceTicks)));
        BigInteger denominator = big(entryPriceTicks)
                .multiply(big(exitPriceTicks))
                .multiply(big(contractSpec.priceTickUnits()));
        return toLongRounded(numerator, denominator);
    }

    private BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }

    private long toLongRounded(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        BigInteger sign = numerator.signum() < 0 ? BigInteger.valueOf(-1L) : BigInteger.ONE;
        BigInteger absolute = numerator.abs();
        BigInteger[] quotientAndRemainder = absolute.divideAndRemainder(denominator);
        BigInteger rounded = quotientAndRemainder[1].shiftLeft(1).compareTo(denominator) >= 0
                ? quotientAndRemainder[0].add(BigInteger.ONE)
                : quotientAndRemainder[0];
        return rounded.multiply(sign).longValueExact();
    }

}
