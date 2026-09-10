package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

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
        boolean changed = updateAccountLeverage(runtime, key, identities.symbolId(key.symbol()), command.leveragePpm());
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
        CoreInstrumentState instrument = runtime.instrument(command.symbol());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        if (instrument.contractType().isOption())
            throw new CoreStateRejectedException("OPTION_LEVERAGE_UNSUPPORTED", "non-portfolio option margin is not leverage based");
        long minimumRate = Math.max(instrument.initialMarginRatePpm(),
                CoreContractMath.riskBracket(instrument, 0).initialMarginRatePpm());
        if (CoreContractMath.initialMarginRateFromLeverage(command.leveragePpm()) < minimumRate)
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT", "leverage exceeds instrument maximum");
        return new CoreLeverageKey(userId, instrument.symbol(), command.marginMode());
    }

    /** 同一账户跨撮合分区的敞口不拆分；写入前完成全部拒绝与溢出检查。 */
    static boolean updateAccountLeverage(TradingRuntimeState runtime, CoreLeverageKey key,
                                         int symbolId, long leveragePpm) {
        Long current = runtime.leverage(key);
        if (current != null && current.longValue() == leveragePpm) return false;
        if (runtime.hasOpenLeverageExposure(key, symbolId))
            throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "open orders or positions exist");
        Math.incrementExact(runtime.revision());
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
        String symbol = OrderReservation.normalizeSymbol(command.symbol());
        String identity = command.positionSide().hedgeSide() ? symbol + ':' + command.positionSide().name() : symbol;
        return identities.positionKey(userId, identity);
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
                position.marginMode(), position.positionSide(), position.instrumentChangeId(), position.signedQuantitySteps(),
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
