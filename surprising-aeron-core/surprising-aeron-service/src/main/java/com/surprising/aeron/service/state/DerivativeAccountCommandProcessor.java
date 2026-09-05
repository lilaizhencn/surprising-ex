package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;

public final class DerivativeAccountCommandProcessor {

    private DerivativeAccountCommandProcessor() {
    }

    public static boolean updatePositionMode(TradingRuntimeState runtime, long userId,
                                             UpdatePositionModeCommand command) {
        if (runtime == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime position mode update");
        }
        runtime.assertOwner();
        if (!runtime.productLine().isDerivative()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "position mode requires derivative product line");
        }
        UserRuntime current = runtime.user(userId);
        com.surprising.aeron.protocol.CorePositionMode currentMode = current == null
                ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : current.positionMode();
        if (currentMode == command.positionMode()) return false;
        boolean[] blocked = {false};
        runtime.positionsForSnapshot().forEachValue(position -> {
            if (position.userId() == userId && position.signedQuantitySteps() != 0) blocked[0] = true;
        });
        runtime.ordersForSnapshot().forEachValue(order -> {
            if (order.userId() == userId && order.status() == CoreOrderStatus.OPEN) blocked[0] = true;
        });
        runtime.reservationsForSnapshot().forEachValue(reservation -> {
            if (reservation.userId() == userId && reservation.reservedUnits() != 0) blocked[0] = true;
        });
        if (blocked[0]) {
            throw new CoreStateRejectedException("POSITION_MODE_SWITCH_BLOCKED",
                    "open positions or orders block position mode update");
        }
        runtime.putUser(new UserRuntime(runtime.productLine(), userId,
                current == null ? 1 : Math.incrementExact(current.revision()), command.positionMode()));
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return true;
    }

    public static boolean updateLeverage(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                         long userId, UpdateLeverageCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime leverage update");
        }
        runtime.assertOwner();
        if (!runtime.productLine().isDerivative()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "leverage requires derivative product line");
        }
        CoreInstrumentState instrument = runtime.instrument(command.symbol());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        }
        if (instrument.contractType().isOption()) {
            throw new CoreStateRejectedException("OPTION_LEVERAGE_UNSUPPORTED",
                    "non-portfolio option margin is not leverage based");
        }
        long minimumRate = Math.max(instrument.initialMarginRatePpm(),
                CoreContractMath.riskBracket(instrument, 0).initialMarginRatePpm());
        if (CoreContractMath.initialMarginRateFromLeverage(command.leveragePpm()) < minimumRate) {
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT",
                    "leverage exceeds instrument maximum");
        }
        int symbolId = identities.symbolId(instrument.symbol());
        boolean[] openState = {false};
        runtime.ordersForSnapshot().forEachValue(order -> {
            if (order.userId() == userId && order.symbolId() == symbolId
                    && order.marginMode() == command.marginMode() && order.status() == CoreOrderStatus.OPEN) {
                openState[0] = true;
            }
        });
        runtime.positionsForSnapshot().forEachValue(position -> {
            if (position.userId() == userId && position.symbolId() == symbolId
                    && position.marginMode() == command.marginMode() && position.signedQuantitySteps() != 0) {
                openState[0] = true;
            }
        });
        CoreLeverageKey key = new CoreLeverageKey(userId, instrument.symbol(), command.marginMode());
        Long current = runtime.leverage(key);
        if (openState[0] && (current == null || current.longValue() != command.leveragePpm())) {
            throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "open orders or positions exist");
        }
        if (current != null && current.longValue() == command.leveragePpm()) return false;
        runtime.putLeverage(key, command.leveragePpm());
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return true;
    }

    public static void adjustPositionMargin(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                            long userId, AdjustPositionMarginCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime position margin adjustment");
        }
        runtime.assertOwner();
        if (command.marginMode() != com.surprising.aeron.protocol.CoreMarginMode.ISOLATED
                || command.amountUnits() == 0) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "only isolated position margin can be adjusted");
        }
        String symbol = OrderReservation.normalizeSymbol(command.symbol());
        String positionIdentity = command.positionSide().hedgeSide()
                ? symbol + ':' + command.positionSide().name() : symbol;
        long positionKey = identities.positionKey(userId, positionIdentity);
        PositionRuntime position = runtime.position(positionKey);
        if (position == null || position.signedQuantitySteps() == 0
                || position.marginMode() != command.marginMode()
                || position.positionSide() != command.positionSide()) {
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "isolated position does not exist");
        }
        BalanceRuntime balance = runtime.balance(userId, position.assetId());
        if (balance == null) {
            throw new IllegalStateException("position margin balance is missing");
        }
        long units = Math.absExact(command.amountUnits());
        long nextMargin;
        if (command.amountUnits() > 0) {
            if (balance.availableUnits() < units) {
                throw new IllegalArgumentException("insufficient runtime balance");
            }
            runtime.replaceBalance(new BalanceRuntime(userId, position.assetId(),
                    balance.availableUnits() - units, Math.addExact(balance.lockedUnits(), units)));
            nextMargin = Math.addExact(position.positionMarginUnits(), units);
        } else {
            if (position.positionMarginUnits() < units) {
                throw new CoreStateRejectedException("POSITION_MARGIN_INSUFFICIENT",
                        "position margin is insufficient");
            }
            if (balance.lockedUnits() < units) throw new IllegalArgumentException("invalid runtime release");
            runtime.replaceBalance(new BalanceRuntime(userId, position.assetId(),
                    Math.addExact(balance.availableUnits(), units), balance.lockedUnits() - units));
            nextMargin = Math.subtractExact(position.positionMarginUnits(), units);
        }
        runtime.replacePosition(positionKey, new PositionRuntime(position.userId(), position.symbolId(),
                position.assetId(), position.marginMode(), position.positionSide(), position.instrumentVersion(),
                position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                position.realizedPnlUnits(), nextMargin));
        runtime.advanceUserRevision(userId);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }
}
