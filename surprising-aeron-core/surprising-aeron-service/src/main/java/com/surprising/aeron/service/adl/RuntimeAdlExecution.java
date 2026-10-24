package com.surprising.aeron.service.adl;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** ADL 步骤 1–4：源强平校验、目标账户减仓、源强平补亏、账本记账。 自动减仓执行：先读取并校验亏空来源的强平记录，再由盈利目标账户的 Lane 减仓和结算，由源账户 Lane 扣减亏空，最后把资金差额交唯一账本。Owner 只安排顺序并收集完成结果。 构造约定：只提供 ADL 执行入口，运行状态由命令槽内的续步保存。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeAdlExecution {

    /**
     * 优先复用已经可回收的命令续步，初始化新 ADL 输入并返回给 Owner 轮询。
     * @param reuse 上次已完成、可再次使用的 ADL 续步，null 或尚不可复用时新建
     * @param command 指定亏空强平、盈利目标、预期持仓版本、减仓数量和覆盖金额的 ADL 命令
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param identities 币对、资产及持仓业务编号与运行时整数键的映射
     */
    public static AdlWork begin(AdlWork reuse, ExecuteAdlCommand command,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid ADL apply");
        }
        runtime.assertOwner();
        // Owner 可读的强平路由，用来定位源用户和合约，不在 Owner 直接遍历账户表。
        var route = runtime.liquidationRoute(command.liquidationId());
        if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation route is missing");
        // 源强平对应的合约配置，检查产品并为目标平仓计算提供金额参数。
        CoreInstrument instrument = requireInstrument(runtime, route.instrumentId());
        // 源强平合约的整数编号，用于检查行情和资金费进度。
        int symbolId = identities.symbolId(route.instrumentId());
        // 与 ADL 命令要求序号一致的当前行情，后续目标平仓估值固定使用它。
        MarkPriceRuntime mark = runtime.markPrice(symbolId);
        if (mark == null || mark.priceSequence() != command.markPriceSequence())
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "ADL mark price changed");
        // 盈利目标用户指定持仓腿的整数键，目标 Lane 稍后核对持仓事实。
        long positionKey = identities.positionKey(command.targetUserId(), instrument, command.positionSide());
        // 新建或已可复用的 ADL 命令续步，保存本次输入和跨线程进度。
        AdlWork work = reuse == null || !reuse.reusable() ? new AdlWork() : reuse;
        work.prepare(command, runtime, instrument, mark, positionKey,
                identities.assetId(instrument.settleAsset()));
        work.fundingInProgress = runtime.treasury().fundingProgress(symbolId) != null;
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(route.userId()), work);
        return work;
    }

    /**
     * 命令槽可复用的 ADL 续步，按源账户校验、目标账户减仓、源强平补亏、账本记账的顺序推进；两个用户同属一条 Lane 时在同一次账户回调内处理。
     */
    public static final class AdlWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        /** 当前 ADL 命令，保存目标持仓预期、减仓量和覆盖额，完成后释放引用。 */
        private ExecuteAdlCommand command;
        /** 源账户 Lane 捕获的强平记录，校验通过后供目标账户计算使用。 */
        private LiquidationRuntime liquidation;
        /** 本次 ADL 固定采用的合约配置引用，完成后释放。 */
        private CoreInstrument instrument;
        /** 与命令价格序号一致的行情输入，目标减仓按它估值。 */
        private MarkPriceRuntime mark;
        /** 盈利目标持仓的整数键，用于读写持仓及登记变更。 */
        private long positionKey;
        /** 目标持仓结算资产的整数编号，余额及总账差额使用同一资产。 */
        private int settleAssetId;
        /** 目标账户算出的清算对手项，完成账户回调后交给账本消费。 */
        private long clearingUnits;
        /** 原有源校验、目标减仓、源补亏、账户发布和账本交接的互斥阶段。 */
        private enum Phase { CAPTURE, TARGET, SOURCE, ACCOUNTS, SUBMIT_LEDGER, LEDGER_WAIT, DONE }
        /** Owner 推进当前阶段；账户阶段在派发前确定，收齐回执后才改变。 */
        private Phase phase;
        /** Owner 准备时捕获的该币对资金费切仓是否进行中，源校验据此拒绝冲突。 */
        private boolean fundingInProgress;

        /**
         * 绑定本次 ADL 的命令和市场输入，清空清算差额并重置续步阶段；源强平记录随后由所属账户 Lane 捕获。
         * @param command 指定亏空强平、盈利目标、预期持仓版本、减仓数量和覆盖金额的 ADL 命令
         * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
         * @param instrument 目标合约的配置，包含产品类型、结算资产及金额换算精度
         * @param mark 已核对序号的标记价及期权风险行情输入
         * @param positionKey 目标用户及合约持仓腿在运行时中的整数键
         * @param settleAssetId 本合约结算资产的整数编号
         */
        private void prepare(ExecuteAdlCommand command, TradingAccountRuntime runtime,
                CoreInstrument instrument, MarkPriceRuntime mark, long positionKey, int settleAssetId) {
            initializeLedgerCommand(runtime);
            this.command = command;

            this.liquidation = null;
            this.instrument = instrument;
            this.mark = mark;
            this.positionKey = positionKey;
            this.settleAssetId = settleAssetId;
            this.clearingUnits = 0;
            this.phase = Phase.CAPTURE;
        }

        /**
         * 在当前账户 Lane 执行对应阶段：先检查源强平状态、资金费边界和覆盖范围；随后由目标 Lane 修改持仓及余额并保存清算差额，源 Lane 更新剩余亏空。
         * @param ignored 通用账户回调签名中的 Lane 参数；实际按当前 Lane 是否拥有源或目标用户决定步骤
         */
        @Override
        public Object apply(int ignored) {
            switch (phase) {
                case CAPTURE -> {
                    liquidation = runtime.liquidation(command.liquidationId());
                    if (liquidation == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
                    if (liquidation.status() != CoreLiquidationState.Status.ADL_REQUIRED)
                        throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "ADL requires ADL state");
                    if (fundingInProgress)
                        throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
                    if (!instrument.instrumentId().equals(command.instrumentId()) || command.targetUserId() == liquidation.userId()
                            || command.coveredUnits() > liquidation.deficitUnits())
                        throw new CoreStateRejectedException("INVALID_COMMAND", "ADL command does not match liquidation");
                }
                case TARGET -> {
                    if (runtime.currentLaneOwns(command.targetUserId()))
                        clearingUnits = applyAdlTarget(command, runtime, liquidation, instrument, mark,
                                positionKey, settleAssetId);
                    if (runtime.currentLaneOwns(liquidation.userId()) && runtime.currentLaneOwns(command.targetUserId()))
                        completeAdlLiquidation(command, runtime);
                }
                case SOURCE -> {
                    if (runtime.currentLaneOwns(liquidation.userId())) completeAdlLiquidation(command, runtime);
                }
                default -> throw new IllegalStateException("ADL account operation outside dispatch phase");
            }
            return null;
        }

        /**
         * Owner 非阻塞等待源校验、目标减仓和必要的源强平更新，登记目标账户变化后派发账本；账本完成才推进全局修订号、释放本次输入并返回 true。
         */
        @Override
        public boolean getAsBoolean() {
            if (phase == Phase.DONE) return true;
            if (phase == Phase.CAPTURE) {
                if (!runtime.pollControlLanes()) return false;
                phase = Phase.TARGET;
                runtime.dispatchControlLanes(runtime.topology().accountLaneMask(command.targetUserId()), this);
                return false;
            }
            if (phase == Phase.TARGET) {
                if (!runtime.pollControlLanes()) return false;
                long sourceLaneMask = runtime.topology().accountLaneMask(liquidation.userId());
                if (runtime.topology().accountLaneMask(command.targetUserId()) != sourceLaneMask) {
                    phase = Phase.SOURCE;
                    runtime.dispatchControlLanes(sourceLaneMask, this);
                    return false;
                }
                phase = Phase.ACCOUNTS;
            }
            if (phase == Phase.SOURCE) {
                if (!runtime.pollControlLanes()) return false;
                phase = Phase.ACCOUNTS;
            }
            if (phase == Phase.ACCOUNTS) {
                runtime.recordUserSettlementChanges(command.targetUserId(), settleAssetId, positionKey);
                runtime.requireRevisionCapacity();
                phase = Phase.SUBMIT_LEDGER;
            }
            if (phase == Phase.SUBMIT_LEDGER) {
                if (!runtime.dispatchLedgerCommand(this)) return false;
                phase = Phase.LEDGER_WAIT;
            }
            if (!ledgerCommandCompleted()) return false;
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            phase = Phase.DONE;
            liquidation = null;
            command = null;
            instrument = null;
            mark = null;
            return true;
        }

        /**
         * 账本消费目标账户的资金差额，完成清算对手项和亏空抵减。
         * @param ledger 当前账本线程持有的资金总账，只有在账本执行阶段应用资金差额
         */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            long nextDeficit = Math.subtractExact(ledger.insuranceDeficit(settleAssetId), command.coveredUnits());
            long nextClearing = Math.addExact(ledger.clearingPnl(settleAssetId), clearingUnits);
            ledger.setInsurance(settleAssetId, ledger.insurance(settleAssetId), nextDeficit);
            ledger.setClearingPnl(settleAssetId, nextClearing);
        }

    }

    /**
     * 目标账户 Lane 校验持仓未变化、方向与源强平相反且平仓盈利足以补亏；按本次减仓量释放保证金、调整现金与持仓并增加用户修订号，返回对应总账差额。
     * @param command 指定亏空强平、盈利目标、预期持仓版本、减仓数量和覆盖金额的 ADL 命令
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param liquidation 源账户 Lane 已捕获并校验的强平记录
     * @param instrument 目标合约的配置，包含产品类型、结算资产及金额换算精度
     * @param mark 已核对序号的标记价及期权风险行情输入
     * @param positionKey 目标用户及合约持仓腿在运行时中的整数键
     * @param settleAssetId 本合约结算资产的整数编号
     */
    private static long applyAdlTarget(ExecuteAdlCommand command, TradingAccountRuntime runtime,
            LiquidationRuntime liquidation, CoreInstrument instrument, MarkPriceRuntime mark,
            long positionKey, int settleAssetId) {
        // 目标用户当前持仓，方向、数量、入场价和保证金模式都必须与命令预期一致。
        PositionRuntime position = runtime.position(positionKey);
        if (position == null || position.marginMode() != command.marginMode()
                || position.signedQuantitySteps() != command.expectedSignedQuantitySteps()
                || position.entryPriceTicks() != command.expectedEntryPriceTicks()
                || Long.signum(position.signedQuantitySteps()) == Long.signum(liquidation.signedQuantitySteps())) {
            throw new CoreStateRejectedException("ADL_POSITION_CONFLICT", "ADL target position changed");
        }
        // 目标持仓按当前标记价计算的全部浮盈，只有正浮盈能提供覆盖能力。
        long totalProfit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks());
        // 目标持仓原绝对数量步数，用于按减仓比例分配盈利和保证金。
        long currentAbs = Math.absExact(position.signedQuantitySteps());
        // 本次减仓量能实现的盈利份额，必须足以覆盖命令指定的补亏额。
        long coverCapacity = totalProfit <= 0 ? 0
                : proportional(totalProfit, command.closeQuantitySteps(), currentAbs);
        if (coverCapacity < command.coveredUnits()) {
            throw new CoreStateRejectedException("ADL_PROFIT_INSUFFICIENT", "ADL target profit is insufficient");
        }
        // 减仓后剩余的绝对数量步数，归零时清空入场信息。
        long remainingAbs = Math.subtractExact(currentAbs, command.closeQuantitySteps());
        // 减仓后的带方向持仓数量，保留原多空方向，全部减完时为 0。
        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        // 按本次减仓比例释放的持仓保证金，从冻结余额转回可用。
        long releasedMargin = proportional(position.positionMarginUnits(), command.closeQuantitySteps(), currentAbs);
        // 目标账户在结算资产上的当前余额，没有该余额时返回业务拒绝。
        BalanceRuntime balance = runtime.balance(command.targetUserId(), settleAssetId);
        if (balance == null) {
            throw new CoreStateRejectedException("BALANCE_NOT_FOUND", "required balance is missing");
        }
        // 本次平仓的现金估值变化，期权用带方向市值，其他合约用本次可实现盈利。
        long closeCashDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument,
                position.signedQuantitySteps() > 0 ? command.closeQuantitySteps()
                        : Math.negateExact(command.closeQuantitySteps()), mark.markPriceTicks())
                : coverCapacity;
        // 平仓现金变化扣掉用于覆盖源亏空的金额后，目标账户实际计入的净现金差额。
        long targetCashDelta = Math.subtractExact(closeCashDelta, command.coveredUnits());
        // 保留原清算对手项的取反与累加顺序，在任何账户写入前检查溢出；亏空抵减直接取命令覆盖额。
        long clearingUnits = Math.addExact(Math.negateExact(targetCashDelta), Math.negateExact(command.coveredUnits()));
        // 目标账户释放保证金并应用净现金差额后的新余额。
        BalanceRuntime nextBalance = new BalanceRuntime(balance.userId(), balance.assetId(),
                Math.addExact(balance.availableUnits(), Math.addExact(releasedMargin, targetCashDelta)),
                Math.subtractExact(balance.lockedUnits(), releasedMargin));
        // 减仓后按剩余数量保留的入场价格量，空仓时清零。
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        // 保留原身份、减小数量并相应释放保证金的目标持仓新值。
        PositionRuntime nextPosition = new PositionRuntime(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), position.instrument(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(),
                        instrument.contractType().isOption() ? 0 : coverCapacity),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin));
        runtime.replaceBalance(nextBalance);
        runtime.replacePosition(positionKey, nextPosition);
        runtime.advanceUserRevision(command.targetUserId());
        return clearingUnits;
    }

    /**
     * 源账户 Lane 从强平剩余亏空中扣除本次覆盖额；归零则完成，否则继续保留 ADL_REQUIRED，执行价格和已收费用保持原记录。
     * @param command 指定亏空强平、盈利目标、预期持仓版本、减仓数量和覆盖金额的 ADL 命令
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     */
    private static void completeAdlLiquidation(ExecuteAdlCommand command, TradingAccountRuntime runtime) {
        // 源账户当前强平记录，需要在原 Lane 上扣减本次实际覆盖金额。
        LiquidationRuntime current = runtime.liquidation(command.liquidationId());
        // 扣除本次 ADL 覆盖后仍未弥补的源强平亏空。
        long nextDeficit = Math.subtractExact(current.deficitUnits(), command.coveredUnits());
        // 剩余亏空为 0 时标记完成，否则保留待 ADL 的阶段。
        CoreLiquidationState.Status nextStatus = nextDeficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;

        // 扣减亏空后的源强平新记录，原执行价格和已收强平费用继续保留。
        LiquidationRuntime nextLiquidation = new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrument(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0);
        runtime.replaceLiquidation(nextLiquidation);
    }

    /**
     * 要求当前运行时存在该合约配置，缺失时返回币对未注册的业务拒绝。
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param instrumentId 需要已经注册在当前运行时的外部合约编号
     */
    private static CoreInstrument requireInstrument(TradingAccountRuntime runtime, String instrumentId) {
        // 源强平对应的合约配置，检查产品并为目标平仓计算提供金额参数。
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }

    /**
     * 按本次数量占原总量的比例分摊整数值并截断小数；全部数量时直接取原值，避免无必要乘法造成中间溢出。
     * @param units 待按数量比例分配的原整数金额或入场价格量
     * @param part 本次分配的数量，调用方应确保其在总数量范围内
     * @param total 用于分摊的原持仓绝对数量，作为正的除数
     */
    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }
}
