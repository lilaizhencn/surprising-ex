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
        requireLinearOrInverse(positionSpec.contractType());
        requireLinearOrInverse(fillSpec.contractType());
        long signedDelta = side == OrderSide.BUY ? quantitySteps : -quantitySteps;
        if (current.signedQuantitySteps() == 0) {
            return new PositionChange(new PositionState(signedDelta, fillSpec.version(), priceTicks,
                    entryValueTicks(Math.absExact(signedDelta), priceTicks),
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
            long newAbs = Math.addExact(oldAbs, addAbs);
            long newEntryValueTicks;
            long weightedPrice;
            if (positionSpec.contractType().isLinear()) {
                newEntryValueTicks = Math.addExact(current.entryValueTicks(), entryValueTicks(addAbs, priceTicks));
                weightedPrice = averageEntryPriceTicks(newEntryValueTicks, newAbs);
            } else {
                weightedPrice = averageEntryPriceTicks(positionSpec.contractType(), oldAbs,
                        current.entryPriceTicks(), addAbs, priceTicks);
                newEntryValueTicks = entryValueTicks(newAbs, weightedPrice);
            }
            return new PositionChange(new PositionState(Math.addExact(currentQty, signedDelta), current.instrumentVersion(),
                    weightedPrice, newEntryValueTicks,
                    current.realizedPnlUnits()), 0L);
        }

        long currentAbs = Math.absExact(currentQty);
        long closeQty = Math.min(currentAbs, Math.absExact(signedDelta));
        long releasedEntryValueTicks = positionSpec.contractType().isLinear()
                ? releasedEntryValueTicks(current.entryValueTicks(), currentAbs, closeQty)
                : 0L;
        long pnlDelta = realizedPnlUnits(currentQty, current.entryPriceTicks(), priceTicks, closeQty, positionSpec,
                releasedEntryValueTicks);
        long newQty = Math.addExact(currentQty, signedDelta);
        long newRealizedPnl = Math.addExact(current.realizedPnlUnits(), pnlDelta);
        long newEntryPrice;
        long newEntryValueTicks;
        long newInstrumentVersion;
        if (newQty == 0L) {
            newEntryPrice = 0L;
            newEntryValueTicks = 0L;
            newInstrumentVersion = 0L;
        } else if (Long.signum(newQty) == Long.signum(currentQty)) {
            newInstrumentVersion = current.instrumentVersion();
            if (positionSpec.contractType().isLinear()) {
                newEntryValueTicks = Math.subtractExact(current.entryValueTicks(), releasedEntryValueTicks);
                newEntryPrice = averageEntryPriceTicks(newEntryValueTicks, Math.absExact(newQty));
            } else {
                newEntryPrice = current.entryPriceTicks();
                newEntryValueTicks = entryValueTicks(Math.absExact(newQty), newEntryPrice);
            }
        } else {
            newEntryPrice = priceTicks;
            newEntryValueTicks = entryValueTicks(Math.absExact(newQty), priceTicks);
            newInstrumentVersion = fillSpec.version();
        }
        return new PositionChange(new PositionState(newQty, newInstrumentVersion, newEntryPrice, newEntryValueTicks,
                newRealizedPnl),
                pnlDelta);
    }

    public PositionChange closeAtSettlement(PositionState current,
                                            long settlementPriceTicks,
                                            ContractSpec contractSpec) {
        if (current == null || contractSpec == null) {
            throw new IllegalArgumentException("position state and contract spec are required");
        }
        if (current.signedQuantitySteps() == 0) {
            return new PositionChange(current, 0L);
        }
        if (settlementPriceTicks <= 0 && !contractSpec.contractType().isOption()) {
            throw new IllegalArgumentException("settlementPriceTicks must be positive");
        }
        if (settlementPriceTicks < 0) {
            throw new IllegalArgumentException("settlementPriceTicks must be non-negative");
        }
        long closeQty = Math.absExact(current.signedQuantitySteps());
        long releasedEntryValueTicks = contractSpec.contractType().isLinear() ? current.entryValueTicks() : 0L;
        long pnlDelta = realizedPnlUnits(current.signedQuantitySteps(), current.entryPriceTicks(),
                settlementPriceTicks, closeQty, contractSpec, releasedEntryValueTicks);
        return new PositionChange(new PositionState(0L, 0L, 0L, 0L,
                Math.addExact(current.realizedPnlUnits(), pnlDelta)), pnlDelta);
    }

    private long averageEntryPriceTicks(ContractType contractType,
                                        long oldAbs,
                                        long oldEntryPriceTicks,
                                        long addAbs,
                                        long addPriceTicks) {
        requireLinearOrInverse(contractType);
        if (contractType.isLinear()) {
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

    private long averageEntryPriceTicks(long entryValueTicks, long quantitySteps) {
        if (quantitySteps <= 0) {
            throw new IllegalArgumentException("quantitySteps must be positive");
        }
        if (entryValueTicks <= 0) {
            throw new IllegalArgumentException("entryValueTicks must be positive");
        }
        return Math.max(1L, entryValueTicks / quantitySteps);
    }

    private long realizedPnlUnits(long currentSignedQty,
                                  long entryPriceTicks,
                                  long exitPriceTicks,
                                  long closeQty,
                                  ContractSpec contractSpec,
                                  long releasedEntryValueTicks) {
        ContractType contractType = contractSpec.contractType();
        requireLinearOrInverse(contractType);
        if (contractType.isLinear()) {
            BigInteger exitValueTicks = big(exitPriceTicks).multiply(big(closeQty));
            BigInteger pnlTicks = currentSignedQty > 0
                    ? exitValueTicks.subtract(big(releasedEntryValueTicks))
                    : big(releasedEntryValueTicks).subtract(exitValueTicks);
            return pnlTicks.multiply(big(contractSpec.notionalMultiplierUnits())).longValueExact();
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

    private long entryValueTicks(long quantitySteps, long priceTicks) {
        return Math.multiplyExact(quantitySteps, priceTicks);
    }

    private long releasedEntryValueTicks(long entryValueTicks, long currentQuantitySteps, long closeQuantitySteps) {
        if (closeQuantitySteps == currentQuantitySteps) {
            return entryValueTicks;
        }
        return big(entryValueTicks).multiply(big(closeQuantitySteps))
                .divide(big(currentQuantitySteps))
                .longValueExact();
    }

    private void requireLinearOrInverse(ContractType contractType) {
        if (contractType == null || (!contractType.isLinear() && !contractType.isInverse())) {
            throw new IllegalArgumentException("unsupported position contract type: " + contractType);
        }
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
