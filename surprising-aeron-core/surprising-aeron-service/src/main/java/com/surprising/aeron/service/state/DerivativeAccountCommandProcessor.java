package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.math.*;

import com.surprising.aeron.service.state.model.CoreLeverageKey;

import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
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
        boolean changed = updateAccountPositionMode(runtime, userId, command);
        if (changed) runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return changed;
    }

    /** 检查完整账户的敞口，不能因订单簿分区不同而遗漏其他币对的持仓和冻结。 */
    static boolean updateAccountPositionMode(TradingRuntimeState runtime, long userId,
                                             UpdatePositionModeCommand command) {
        if (!runtime.productLine().isDerivative())
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "position mode requires derivative product line");
        UserRuntime current = runtime.user(userId);
        var currentMode = current == null ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : current.positionMode();
        if (currentMode == command.positionMode()) return false;
        if (runtime.hasOpenAccountExposure(userId))
            throw new CoreStateRejectedException("POSITION_MODE_SWITCH_BLOCKED", "open positions or orders block position mode update");
        Math.incrementExact(runtime.revision()); // 首次写入前检查提交修订号；无变化的命令无需递增。
        runtime.putUser(new UserRuntime(runtime.productLine(), userId,
                current == null ? 1 : Math.incrementExact(current.revision()), command.positionMode()));
        return true;
    }

    public static boolean updateLeverage(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                         long userId, UpdateLeverageCommand command) {
        if (runtime == null) throw new IllegalArgumentException("invalid runtime leverage update");
        CoreLeverageKey key = leverageKey(runtime, identities, userId, command);
        boolean changed = updateAccountLeverage(runtime, key, identities.symbolId(key.instrumentId()),
                command.leveragePpm(), command.repriceCrossMargin());
        if (changed) runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return changed;
    }

    /** Owner 校验产品与币对规则；账户状态由所属 Lane 检查。 */
    static CoreLeverageKey leverageKey(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                       long userId, UpdateLeverageCommand command) {
        runtime.assertOwner();
        if (userId <= 0 || command == null || identities == null)
            throw new IllegalArgumentException("invalid runtime leverage update");
        if (!runtime.productLine().isDerivative())
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "leverage requires derivative product line");
        CoreInstrument instrument = runtime.instrument(command.instrumentId());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        if (instrument.contractType().isOption())
            throw new CoreStateRejectedException("OPTION_LEVERAGE_UNSUPPORTED", "non-portfolio option margin is not leverage based");
        if (command.repriceCrossMargin()) {
            int symbolId = identities.symbolId(instrument.instrumentId());
            if (runtime.treasury().fundingProgress(symbolId) != null
                    || runtime.treasury().lifecycleSettlement(symbolId) != 0)
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "position settlement blocks margin repricing");
            if (command.leveragePpm() > instrument.maxLeveragePpm())
                throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT", "leverage exceeds instrument maximum");
        }
        long minimumRate = Math.max(instrument.initialMarginRatePpm(),
                CoreContractMath.riskBracket(instrument, 0).initialMarginRatePpm());
        if (CoreContractMath.initialMarginRateFromLeverage(command.leveragePpm()) < minimumRate)
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT", "leverage exceeds instrument maximum");
        return new CoreLeverageKey(userId, instrument.instrumentId(), command.marginMode());
    }

    /** 同一账户跨撮合分区的敞口不拆分；写入前完成全部拒绝与溢出检查。 */
    static boolean updateAccountLeverage(TradingRuntimeState runtime, CoreLeverageKey key,
                                         int symbolId, long leveragePpm) {
        return updateAccountLeverage(runtime, key, symbolId, leveragePpm, false);
    }

    static boolean updateAccountLeverage(TradingRuntimeState runtime, CoreLeverageKey key,
                                         int symbolId, long leveragePpm, boolean repriceCrossMargin) {
        if (repriceCrossMargin) return runtime.onLane(key.userId(), lane ->
                lowerCrossLeverage(runtime, key, symbolId, leveragePpm, lane));
        Long current = runtime.leverage(key);
        if (current != null && current.longValue() == leveragePpm) return false;
        if (runtime.hasOpenLeverageExposure(key, symbolId))
            throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "open orders or positions exist");
        Math.incrementExact(runtime.revision());
        runtime.putLeverage(key, leveragePpm);
        return true;
    }

    /** 显式的新命令：先撤完报价，再一次性补足全仓持仓保证金；旧日志不进入此路径。 */
    private static boolean lowerCrossLeverage(TradingRuntimeState runtime, CoreLeverageKey key,
            int symbolId, long leveragePpm, AccountLaneState lane) {
        if (key.marginMode() != com.surprising.aeron.protocol.CoreMarginMode.CROSS)
            throw new CoreStateRejectedException("LEVERAGE_REPRICE_REQUIRES_CROSS", "cross margin is required");
        CoreInstrument instrument = runtime.instrument(key.instrumentId());
        Long configured = runtime.leverage(key);
        long previous = configured == null ? instrument.maxLeveragePpm() : configured;
        if (configured != null && previous == leveragePpm) return false;
        if (leveragePpm > previous)
            throw new CoreStateRejectedException("LEVERAGE_REPRICE_INCREASE_BLOCKED", "only lowering leverage is supported");
        var orderIds = lane.activeOrderIdsByUser.get(key.userId());
        if (orderIds != null) {
            var it = orderIds.longIterator();
            while (it.hasNext()) {
                var order = lane.orders.get(it.next());
                if (order != null && order.symbolId() == symbolId && order.marginMode() == key.marginMode()
                        && order.status() == com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN)
                    throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "cancel open orders before repricing margin");
            }
        }
        Math.incrementExact(runtime.revision());
        var positions = new java.util.ArrayList<java.util.Map.Entry<Long, PositionRuntime>>();
        long additional = 0;
        int assetId = -1;
        for (long positionKey : runtime.positionKeysForUserAndSymbol(key.userId(), symbolId)) {
            PositionRuntime position = runtime.position(positionKey);
            if (position == null || position.marginMode() != key.marginMode() || position.signedQuantitySteps() == 0) continue;
            if (runtime.activeLiquidation(key.userId(), symbolId, position.positionSide()) != null)
                throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "liquidation is in progress");
            var mark = runtime.markPrice(symbolId);
            if (mark == null || mark.markPriceTicks() <= 0)
                throw new CoreStateRejectedException("MARK_PRICE_UNAVAILABLE", "position margin requires a mark price");
            long quantity = Math.absExact(position.signedQuantitySteps());
            long notional = CoreContractMath.riskNotionalUnits(instrument, quantity, mark.markPriceTicks());
            var bracket = CoreContractMath.riskBracket(instrument, notional);
            if (notional > bracket.notionalCapUnits() || leveragePpm > bracket.maxLeveragePpm()
                    || CoreContractMath.initialMarginRateFromLeverage(leveragePpm) < bracket.initialMarginRatePpm())
                throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_RISK_BRACKET", "target leverage exceeds position risk bracket");
            long margin = Math.max(position.positionMarginUnits(), Math.max(
                    com.surprising.aeron.service.business.derivative.FuturesFillCalculator.openingMarginForFill(
                            instrument, position.signedQuantitySteps(), position.signedQuantitySteps(), quantity,
                            position.entryPriceTicks(), leveragePpm, mark),
                    com.surprising.aeron.service.business.derivative.FuturesFillCalculator.openingMarginForFill(
                            instrument, position.signedQuantitySteps(), position.signedQuantitySteps(), quantity,
                            mark.markPriceTicks(), leveragePpm, mark)));
            additional = Math.addExact(additional, Math.subtractExact(margin, position.positionMarginUnits()));
            if (assetId != -1 && assetId != position.assetId()) throw new IllegalStateException("position settlement asset mismatch");
            assetId = position.assetId();
            positions.add(java.util.Map.entry(positionKey, new PositionRuntime(position.userId(), symbolId,
                    assetId, position.marginMode(), position.positionSide(), position.instrument(),
                    position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                    position.realizedPnlUnits(), margin)));
        }
        if (!positions.isEmpty()) {
            BalanceRuntime balance = runtime.balance(key.userId(), assetId);
            if (balance == null || balance.availableUnits() < additional)
                throw new CoreStateRejectedException("INSUFFICIENT_BALANCE", "insufficient available funds for lower leverage");
            BalanceRuntime next = new BalanceRuntime(key.userId(), assetId,
                    Math.subtractExact(balance.availableUnits(), additional), Math.addExact(balance.lockedUnits(), additional));
            UserRuntime user = runtime.requireUser(key.userId());
            UserRuntime nextUser = new UserRuntime(user.productLine(), user.userId(),
                    Math.incrementExact(user.revision()), user.positionMode());
            // All business rejection and arithmetic checks precede the first mutation.
            runtime.replaceBalance(next);
            for (var position : positions) runtime.replacePosition(position.getKey(), position.getValue());
            runtime.putUser(nextUser);
        }
        runtime.putLeverage(key, leveragePpm);
        return true;
    }

    public static void adjustPositionMargin(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                            long userId, AdjustPositionMarginCommand command) {
        long key = positionMarginKey(runtime, identities, userId, command);
        long nextRevision = Math.incrementExact(runtime.revision());
        adjustAccountPositionMargin(runtime, userId, key, command);
        runtime.setMetadata(runtime.productLine(), nextRevision);
    }

    /** 身份解析仅在 Owner 完成；不读取或复制其他账户。 */
    static long positionMarginKey(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                  long userId, AdjustPositionMarginCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid runtime position margin adjustment");
        runtime.assertOwner();
        if (command.marginMode() != com.surprising.aeron.protocol.CoreMarginMode.ISOLATED || command.amountUnits() == 0)
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID", "only isolated position margin can be adjusted");
        String instrumentId = OrderReservation.requireInstrumentId(command.instrumentId());
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        return identities.positionKey(userId, instrument, command.positionSide());
    }

    /** 在同一账户 Lane 内原子地转移可用资金与逐仓保证金，写入前完成全部算术校验。 */
    static int adjustAccountPositionMargin(TradingRuntimeState runtime, long userId, long positionKey,
                                           AdjustPositionMarginCommand command) {
        PositionRuntime position = runtime.position(positionKey);
        if (position == null || position.signedQuantitySteps() == 0
                || position.marginMode() != command.marginMode() || position.positionSide() != command.positionSide())
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "isolated position does not exist");
        BalanceRuntime balance = runtime.balance(userId, position.assetId());
        if (balance == null) throw new IllegalStateException("position margin balance is missing");
        long units = Math.absExact(command.amountUnits());
        long nextMargin;
        BalanceRuntime nextBalance;
        if (command.amountUnits() > 0) {
            if (balance.availableUnits() < units) throw new IllegalArgumentException("insufficient runtime balance");
            nextMargin = Math.addExact(position.positionMarginUnits(), units);
            nextBalance = new BalanceRuntime(userId, position.assetId(),
                    balance.availableUnits() - units, Math.addExact(balance.lockedUnits(), units));
        } else {
            if (position.positionMarginUnits() < units)
                throw new CoreStateRejectedException("POSITION_MARGIN_INSUFFICIENT", "position margin is insufficient");
            if (balance.lockedUnits() < units) throw new IllegalArgumentException("invalid runtime release");
            nextMargin = Math.subtractExact(position.positionMarginUnits(), units);
            nextBalance = new BalanceRuntime(userId, position.assetId(),
                    Math.addExact(balance.availableUnits(), units), balance.lockedUnits() - units);
        }
        PositionRuntime nextPosition = new PositionRuntime(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), position.instrument(), position.signedQuantitySteps(),
                position.entryPriceTicks(), position.entryValueTicks(), position.realizedPnlUnits(), nextMargin);
        UserRuntime user = runtime.requireUser(userId);
        UserRuntime nextUser = new UserRuntime(user.productLine(), userId,
                Math.incrementExact(user.revision()), user.positionMode());
        runtime.replaceBalance(nextBalance);
        runtime.replacePosition(positionKey, nextPosition);
        runtime.putUser(nextUser);
        return position.assetId();
    }
}
