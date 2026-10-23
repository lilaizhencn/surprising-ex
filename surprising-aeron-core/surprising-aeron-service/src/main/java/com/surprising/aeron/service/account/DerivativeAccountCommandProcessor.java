package com.surprising.aeron.service.account;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 衍生品账户设置与保证金变更。Owner 侧解析产品及合约规则，账户步骤按用户所属 Lane 检查完整敞口；同步入口还负责产品修订发布，异步入口把结果交回控制续步。 构造约定：只提供账户业务步骤，不保存第二份用户或保证金状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DerivativeAccountCommandProcessor {

    /**
     * 校验输入与衍生产品线，执行账户模式切换；只有确实切换时才增加产品修订号。
     * @param runtime 处理当前用户所属产品线的交易运行时
     * @param userId 需要改变账户设置或保证金的用户编号
     * @param command 目标持仓模式命令
     */
    public static boolean updatePositionMode(TradingAccountRuntime runtime, long userId,
                                             UpdatePositionModeCommand command) {
        if (runtime == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime position mode update");
        }
        runtime.assertOwner();
        if (!runtime.productLine().isDerivative()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "position mode requires derivative product line");
        }
        // 账户步骤是否实际改变设置，只有变化时同步入口才发布产品修订。
        boolean changed = updateAccountPositionMode(runtime, userId, command);
        if (changed) runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return changed;
    }

    /**
     * 目标模式相同直接返回未变化；有跨合约订单或持仓敞口则拒绝，否则预检查产品和用户修订容量后更新用户模式。
     * @param runtime 处理当前用户所属产品线的交易运行时
     * @param userId 需要改变账户设置或保证金的用户编号
     * @param command 目标持仓模式命令
     */
    public static boolean updateAccountPositionMode(TradingAccountRuntime runtime, long userId,
                                             UpdatePositionModeCommand command) {
        if (!runtime.productLine().isDerivative())
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "position mode requires derivative product line");
        // 当前已有用户元数据；不存在时后续以单向持仓模式作比较。
        UserRuntime current = runtime.user(userId);
        // 已有用户持仓模式，用户尚不存在时按单向模式比较。
        var currentMode = current == null ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : current.positionMode();
        if (currentMode == command.positionMode()) return false;
        if (runtime.hasOpenAccountExposure(userId))
            throw new CoreStateRejectedException("POSITION_MODE_SWITCH_BLOCKED", "open positions or orders block position mode update");
        Math.incrementExact(runtime.revision()); // 首次写入前检查提交修订号；无变化的命令无需递增。
        runtime.setUserState(userId, current == null ? 1 : Math.incrementExact(current.revision()),
                command.positionMode());
        return true;
    }

    /**
     * 解析并校验杠杆业务键，执行账户杠杆步骤，实际变化后才发布下一产品修订号。
     * @param runtime 处理当前用户所属产品线的交易运行时
     * @param identities 解析稠密合约及运行时持仓键的字典
     * @param userId 需要改变账户设置或保证金的用户编号
     * @param command 合约、模式、杠杆及重定价开关命令
     */
    public static boolean updateLeverage(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                         long userId, UpdateLeverageCommand command) {
        if (runtime == null) throw new IllegalArgumentException("invalid runtime leverage update");
        // 产品及合约校验后得到的杠杆业务键，或保证金路径解析出的持仓键。
        CoreLeverageKey key = leverageKey(runtime, identities, userId, command);
        // 账户步骤是否实际改变设置，只有变化时同步入口才发布产品修订。
        boolean changed = updateAccountLeverage(runtime, key, identities.symbolId(key.instrumentId()),
                command.leveragePpm(), command.repriceCrossMargin());
        if (changed) runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return changed;
    }

    /**
     * 在 Owner 侧校验衍生品、合约及风险档位要求；期权不使用该杠杆模式，显式全仓重定价还检查生命周期冲突，再构造杠杆业务键。
     * @param runtime 处理当前用户所属产品线的交易运行时
     * @param identities 解析稠密合约及运行时持仓键的字典
     * @param userId 需要改变账户设置或保证金的用户编号
     * @param command 要进行产品与合约规则校验的杠杆命令
     */
    static CoreLeverageKey leverageKey(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                       long userId, UpdateLeverageCommand command) {
        runtime.assertOwner();
        if (userId <= 0 || command == null || identities == null)
            throw new IllegalArgumentException("invalid runtime leverage update");
        if (!runtime.productLine().isDerivative())
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "leverage requires derivative product line");
        // 已经注册的目标合约配置，提供杠杆、资产及资金计算规则。
        CoreInstrument instrument = runtime.instrument(command.instrumentId());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        if (instrument.contractType().isOption())
            throw new CoreStateRejectedException("OPTION_LEVERAGE_UNSUPPORTED", "non-portfolio option margin is not leverage based");
        if (command.repriceCrossMargin()) {
            // 业务合约映射后的稠密编号，用于读取资金费或到期进度。
            int symbolId = identities.symbolId(instrument.instrumentIdValue());
            if (runtime.treasury().fundingProgress(symbolId) != null
                    || runtime.treasury().lifecycleSettlement(symbolId) != 0)
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "position settlement blocks margin repricing");
            if (command.leveragePpm() > instrument.maxLeveragePpm())
                throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT", "leverage exceeds instrument maximum");
        }
        // 合约初始保证金率和零名义价值档位初始保证金率的较大值。
        long minimumRate = Math.max(instrument.initialMarginRatePpm(),
                CoreContractMath.riskBracket(instrument, 0).initialMarginRatePpm());
        if (CoreContractMath.initialMarginRateFromLeverage(command.leveragePpm()) < minimumRate)
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT", "leverage exceeds instrument maximum");
        return new CoreLeverageKey(userId, instrument.instrumentId(), command.marginMode());
    }

    /**
     * 显式重定价时进入同一账户 Lane 的补保证金路径；普通设置相同则无变化，有相关敞口则拒绝，否则记录新杠杆。
     * @param runtime 处理当前用户所属产品线的交易运行时
     * @param key 用户、合约与保证金模式组成的杠杆业务键
     * @param symbolId 目标合约的稠密编号
     * @param leveragePpm 目标杠杆，按百万分之一保存
     * @param repriceCrossMargin 是否显式降低全仓杠杆并按持仓补足保证金
     */
    static boolean updateAccountLeverage(TradingAccountRuntime runtime, CoreLeverageKey key,
                                         int symbolId, long leveragePpm, boolean repriceCrossMargin) {
        if (repriceCrossMargin) return runtime.onLane(key.userId(), /* onLane 回调提供的用户所属 Lane，全仓重定价只读写这个账户分区。 */ lane ->
                lowerCrossLeverage(runtime, key, symbolId, leveragePpm, lane));
        // 当前杠杆键的显式设置；缺失为 null，不等同于已保存了目标杠杆。
        Long current = runtime.leverage(key);
        if (current != null && current.longValue() == leveragePpm) return false;
        if (runtime.hasOpenLeverageExposure(key, symbolId))
            throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "open orders or positions exist");
        Math.incrementExact(runtime.revision());
        runtime.putLeverage(key, leveragePpm);
        return true;
    }

    /**
     * 只允许降低全仓杠杆。先要求相关 OPEN 订单已撤、逐仓位校验行情及档位并算好所需追加保证金，再检查余额和修订容量，最后写余额、持仓、用户和杠杆。
     * @param runtime 处理当前用户所属产品线的交易运行时
     * @param key 用户、合约与保证金模式组成的杠杆业务键
     * @param symbolId 目标合约的稠密编号
     * @param leveragePpm 目标杠杆，按百万分之一保存
     * @param lane 当前实际拥有该用户账户数据的 Lane
     */
    private static boolean lowerCrossLeverage(TradingAccountRuntime runtime, CoreLeverageKey key,
            int symbolId, long leveragePpm, AccountLaneState lane) {
        if (key.marginMode() != com.surprising.aeron.protocol.CoreMarginMode.CROSS)
            throw new CoreStateRejectedException("LEVERAGE_REPRICE_REQUIRES_CROSS", "cross margin is required");
        // 已经注册的目标合约配置，提供杠杆、资产及资金计算规则。
        CoreInstrument instrument = runtime.instrument(key.instrumentId());
        // 用户原来显式配置的杠杆，缺失时以合约最大杠杆作比较基准。
        Long configured = runtime.leverage(key);
        // 重定价之前的有效杠杆，目标只允许相同或更低。
        long previous = configured == null ? instrument.maxLeveragePpm() : configured;
        if (configured != null && previous == leveragePpm) return false;
        if (leveragePpm > previous)
            throw new CoreStateRejectedException("LEVERAGE_REPRICE_INCREASE_BLOCKED", "only lowering leverage is supported");
        // 该用户活跃订单编号集合，检查目标合约及模式中是否仍有 OPEN 订单。
        var orderIds = lane.activeOrderIdsByUser.get(key.userId());
        if (orderIds != null) {
            // 活跃订单编号集合的原语迭代器。
            var it = orderIds.iterator();
            while (it.hasNext()) {
                // 迭代器当前编号对应的 Lane 权威订单。
                var order = lane.orders.get(it.nextValue());
                if (order != null && order.symbolId() == symbolId && order.marginMode() == key.marginMode()
                        && order.status() == com.surprising.aeron.model.CoreOrderStatus.OPEN)
                    throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "cancel open orders before repricing margin");
            }
        }
        Math.incrementExact(runtime.revision());
        // 检查完成后准备写回的持仓键与新持仓值列表，计算期间不先改原持仓。
        var positions = new java.util.ArrayList<java.util.Map.Entry<Long, PositionRuntime>>();
        // 本次所有相关持仓累计需要补充的非负保证金金额。
        long additional = 0;
        // 本批相关持仓共同的结算资产编号，初始 -1 表示尚未选定。
        int assetId = -1;
        // 该用户该合约的有序原语持仓键集合。
        var positionKeys = runtime.positionKeysForUserAndSymbolPrimitive(key.userId(), symbolId);
        for (/* 当前持仓键在原语集合中的下标。 */ int i = 0; i < positionKeys.size(); i++) {
            // 当前需要检查并重定价的持仓键。
            long positionKey = positionKeys.get(i);
            // 当前 Lane 权威持仓，最终写回循环中则为已计算的新持仓键值项。
            PositionRuntime position = lane.positions.get(positionKey);
            if (position == null || position.marginMode() != key.marginMode() || position.signedQuantitySteps() == 0) continue;
            if (runtime.activeLiquidation(key.userId(), symbolId, position.positionSide()) != null)
                throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "liquidation is in progress");
            // 本次保证金估值使用的正数标记价及行情参数。
            var mark = runtime.markPrice(symbolId);
            if (mark == null || mark.markPriceTicks() <= 0)
                throw new CoreStateRejectedException("MARK_PRICE_UNAVAILABLE", "position margin requires a mark price");
            // 用精确绝对值取得的不带方向持仓数量步数。
            long quantity = Math.absExact(position.signedQuantitySteps());
            // 按当前标记价计算的风险名义价值。
            long notional = CoreContractMath.riskNotionalUnits(instrument, quantity, mark.markPriceTicks());
            // 当前风险名义价值对应的杠杆及保证金档位。
            var bracket = CoreContractMath.riskBracket(instrument, notional);
            if (notional > bracket.notionalCapUnits() || leveragePpm > bracket.maxLeveragePpm()
                    || CoreContractMath.initialMarginRateFromLeverage(leveragePpm) < bracket.initialMarginRatePpm())
                throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_RISK_BRACKET", "target leverage exceeds position risk bracket");
            // 原保证金、按入场价与标记价计算的目标保证金三者的最大值，不在降杠杆时释放原保证金。
            long margin = Math.max(position.positionMarginUnits(), Math.max(
                    com.surprising.aeron.service.product.derivative.FuturesFillCalculator.openingMarginForFill(
                            instrument, position.signedQuantitySteps(), position.signedQuantitySteps(), quantity,
                            position.entryPriceTicks(), leveragePpm, mark),
                    com.surprising.aeron.service.product.derivative.FuturesFillCalculator.openingMarginForFill(
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
            // 目标结算资产的权威可用与冻结余额。
            BalanceRuntime balance = runtime.balance(key.userId(), assetId);
            if (balance == null || balance.availableUnits() < additional)
                throw new CoreStateRejectedException("INSUFFICIENT_BALANCE", "insufficient available funds for lower leverage");
            // 追加或取回保证金后应写入的可用余额。
            long nextAvailable = Math.subtractExact(balance.availableUnits(), additional);
            // 追加或取回保证金后应写入的冻结余额。
            long nextLocked = Math.addExact(balance.lockedUnits(), additional);
            // 本次变更的用户权威元数据。
            UserRuntime user = runtime.requireUser(key.userId());
            // 写入账户前已精确算好的下一用户修订号。
            long userRevision = Math.incrementExact(user.revision());
            // 先完成本段的业务拒绝判断及金额、用户修订算术，再进入余额和持仓写入。
            runtime.replaceBalance(key.userId(), assetId, nextAvailable, nextLocked);
            for (var /* 当前 Lane 权威持仓，最终写回循环中则为已计算的新持仓键值项。 */ position : positions) runtime.replacePosition(position.getKey(), position.getValue());
            runtime.setUserState(user.userId(), userRevision, user.positionMode());
        }
        runtime.putLeverage(key, leveragePpm);
        return true;
    }

    /**
     * 解析逐仓持仓身份并预计算产品修订号，完成账户保证金调整后发布该修订。
     * @param runtime 处理当前用户所属产品线的交易运行时
     * @param identities 解析稠密合约及运行时持仓键的字典
     * @param userId 需要改变账户设置或保证金的用户编号
     * @param command 有符号逐仓保证金增减命令
     */
    public static void adjustPositionMargin(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                            long userId, AdjustPositionMarginCommand command) {
        // 产品及合约校验后得到的杠杆业务键，或保证金路径解析出的持仓键。
        long key = positionMarginKey(runtime, identities, userId, command);
        // 同步保证金入口预先算好的下一产品修订号。
        long nextRevision = Math.incrementExact(runtime.revision());
        adjustAccountPositionMargin(runtime, userId, key, command);
        runtime.setMetadata(runtime.productLine(), nextRevision);
    }

    /**
     * 核对非零逐仓调整输入、合约存在性并解析用户持仓腿键；这里不读取其他用户账户，也不在此判定持仓是否仍有仓。
     * @param runtime 处理当前用户所属产品线的交易运行时
     * @param identities 解析稠密合约及运行时持仓键的字典
     * @param userId 需要改变账户设置或保证金的用户编号
     * @param command 含合约、持仓腿、逐仓模式和非零调整额的命令
     */
    static long positionMarginKey(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                  long userId, AdjustPositionMarginCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid runtime position margin adjustment");
        runtime.assertOwner();
        if (command.amountUnits() == 0)
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID", "only isolated position margin can be adjusted");
        // 经过规则校验的业务合约编号文本。
        String instrumentId = OrderReservation.requireInstrumentId(command.instrumentId());
        // 已经注册的目标合约配置，提供杠杆、资产及资金计算规则。
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        return identities.positionKey(userId, instrument, command.positionSide());
    }

    /**
     * 在所属账户中校验逐仓持仓及余额，先计算增减保证金后的全部金额和用户版本，再写余额、持仓与用户状态，返回涉及的资产编号。
     * @param runtime 处理当前用户所属产品线的交易运行时
     * @param userId 需要改变账户设置或保证金的用户编号
     * @param positionKey 已解析的用户合约持仓腿运行时键
     * @param command 已解析身份、准备在账户应用的逐仓保证金命令
     */
    static int adjustAccountPositionMargin(TradingAccountRuntime runtime, long userId, long positionKey,
                                           AdjustPositionMarginCommand command) {
        // 当前 Lane 权威持仓，最终写回循环中则为已计算的新持仓键值项。
        PositionRuntime position = runtime.position(positionKey);
        if (position == null || position.signedQuantitySteps() == 0
                || position.marginMode() != com.surprising.aeron.protocol.CoreMarginMode.ISOLATED || position.positionSide() != command.positionSide())
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "isolated position does not exist");
        // 目标结算资产的权威可用与冻结余额。
        BalanceRuntime balance = runtime.balance(userId, position.assetId());
        if (balance == null) throw new IllegalStateException("position margin balance is missing");
        // 逐仓调整金额的精确绝对值，正负方向另由原命令判断。
        long units = Math.absExact(command.amountUnits());
        // 调整后要写入持仓的保证金金额。
        long nextMargin;
        // 追加或取回保证金后应写入的可用余额。
        long nextAvailable;
        // 追加或取回保证金后应写入的冻结余额。
        long nextLocked;
        if (command.amountUnits() > 0) {
            if (balance.availableUnits() < units) throw new IllegalArgumentException("insufficient runtime balance");
            nextMargin = Math.addExact(position.positionMarginUnits(), units);
            nextAvailable = Math.subtractExact(balance.availableUnits(), units);
            nextLocked = Math.addExact(balance.lockedUnits(), units);
        } else {
            if (position.positionMarginUnits() < units)
                throw new CoreStateRejectedException("POSITION_MARGIN_INSUFFICIENT", "position margin is insufficient");
            if (balance.lockedUnits() < units) throw new IllegalArgumentException("invalid runtime release");
            nextMargin = Math.subtractExact(position.positionMarginUnits(), units);
            nextAvailable = Math.addExact(balance.availableUnits(), units);
            nextLocked = Math.subtractExact(balance.lockedUnits(), units);
        }
        // 金额和版本校验后构造的新持仓值，其他持仓业务字段保留。
        PositionRuntime nextPosition = new PositionRuntime(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), position.instrument(), position.signedQuantitySteps(),
                position.entryPriceTicks(), position.entryValueTicks(), position.realizedPnlUnits(), nextMargin);
        // 本次变更的用户权威元数据。
        UserRuntime user = runtime.requireUser(userId);
        // 写入账户前已精确算好的下一用户修订号。
        long userRevision = Math.incrementExact(user.revision());
        runtime.replaceBalance(userId, position.assetId(), nextAvailable, nextLocked);
        runtime.replacePosition(positionKey, nextPosition);
        runtime.setUserState(userId, userRevision, user.positionMode());
        return position.assetId();
    }
}
