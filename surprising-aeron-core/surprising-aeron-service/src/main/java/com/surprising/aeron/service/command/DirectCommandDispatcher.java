package com.surprising.aeron.service.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.command.adl.AdlCommands;
import com.surprising.aeron.service.command.balance.BalanceTransferCommands;
import com.surprising.aeron.service.command.fee.FeePolicyCommands;
import com.surprising.aeron.service.command.funding.PerpetualFundingCommands;
import com.surprising.aeron.service.command.insurance.InsuranceFundCommands;
import com.surprising.aeron.service.command.instrument.InstrumentConfigurationCommands;
import com.surprising.aeron.service.command.leverage.LeverageCommands;
import com.surprising.aeron.service.command.liquidation.LiquidationCommands;
import com.surprising.aeron.service.command.position.PositionCommands;
import com.surprising.aeron.service.command.risk.RiskCommands;
import com.surprising.aeron.service.command.trigger.TriggerOrderCommands;

/**
 * Owner 按协议消息类型寻找对应业务入口的分发表，覆盖余额、配置、风险和条件单等直接命令。部分入口仍需账户、Matcher 或账本异步续步；派发命中不等于业务已经成功或提交。这里不解码业务正文，也不自行回滚。
 */
public final class DirectCommandDispatcher {
    /** 余额调整与跨产品划转命令入口。 */
    private final BalanceTransferCommands balances;
    /** 合约注册、配置及维护状态入口。 */
    private final InstrumentConfigurationCommands instruments;
    /** 永续资金费命令入口。 */
    private final PerpetualFundingCommands funding;
    /** 标记价、风控续扫与扫描控制入口。 */
    private final RiskCommands risk;
    /** 强平结果确认入口，强平撮合执行另走交易编排。 */
    private final LiquidationCommands liquidations;
    /** 自动减仓命令入口。 */
    private final AdlCommands adl;
    /** 保险基金调整入口。 */
    private final InsuranceFundCommands insurance;
    /** 持仓模式及持仓保证金调整入口。 */
    private final PositionCommands positions;
    /** 杠杆变更入口。 */
    private final LeverageCommands leverage;
    /** 费用政策配置入口。 */
    private final FeePolicyCommands fees;
    /** 算法单、延时全撤及触发单生命周期入口。 */
    private final TriggerOrderCommands triggers;

    /**
     * 接入全部既有业务处理入口并逐项要求非空，避免运行中遇到缺失分支。
     * @param balances 余额调整与跨产品划转命令入口
     * @param instruments 合约注册、配置及维护状态入口
     * @param funding 永续资金费命令入口
     * @param risk 标记价、风控续扫与扫描控制入口
     * @param liquidations 强平结果确认入口，强平撮合执行另走交易编排
     * @param adl 自动减仓命令入口
     * @param insurance 保险基金调整入口
     * @param positions 持仓模式及持仓保证金调整入口
     * @param leverage 杠杆变更入口
     * @param fees 费用政策配置入口
     * @param triggers 算法单、延时全撤及触发单生命周期入口
     */
    public DirectCommandDispatcher(BalanceTransferCommands balances,
                                   InstrumentConfigurationCommands instruments,
                                   PerpetualFundingCommands funding,
                                   RiskCommands risk,
                                   LiquidationCommands liquidations,
                                   AdlCommands adl,
                                   InsuranceFundCommands insurance,
                                   PositionCommands positions,
                                   LeverageCommands leverage,
                                   FeePolicyCommands fees,
                                   TriggerOrderCommands triggers) {
        this.balances = java.util.Objects.requireNonNull(balances);
        this.instruments = java.util.Objects.requireNonNull(instruments);
        this.funding = java.util.Objects.requireNonNull(funding);
        this.risk = java.util.Objects.requireNonNull(risk);
        this.liquidations = java.util.Objects.requireNonNull(liquidations);
        this.adl = java.util.Objects.requireNonNull(adl);
        this.insurance = java.util.Objects.requireNonNull(insurance);
        this.positions = java.util.Objects.requireNonNull(positions);
        this.leverage = java.util.Objects.requireNonNull(leverage);
        this.fees = java.util.Objects.requireNonNull(fees);
        this.triggers = java.util.Objects.requireNonNull(triggers);
    }

    /**
     * 命中支持的消息类型就调用相应入口，正常返回后给真；未匹配返回假，由调用方继续撮合或控制流程。入口抛出的异常直接传播，真值只表示已派发，不表示异步命令已经完成。
     * @param message 当前借用的原消息，其头部决定分支，载荷由业务入口解码
     * @param clusterTimestamp 该条复制命令的业务时间，传给需要它的业务入口
     */
    public boolean dispatch(CoreMessage message, long clusterTimestamp) {
        switch (message.header().messageType()) {
            case ADJUST_BALANCE -> balances.executeAdjustBalance(message, clusterTimestamp);
            case TRANSFER_OUT -> balances.executeTransferOut(message, clusterTimestamp);
            case TRANSFER_IN -> balances.executeTransferIn(message, clusterTimestamp);
            case COMPLETE_TRANSFER -> balances.executeCompleteTransfer(message, clusterTimestamp);
            case REGISTER_INSTRUMENT -> instruments.executeRegisterInstrument(message, clusterTimestamp);
            case APPLY_MARK_PRICE -> risk.executeApplyMarkPrice(message, clusterTimestamp);
            case APPLY_FUNDING -> funding.executeApplyFunding(message, clusterTimestamp);
            case EXECUTE_ADL -> adl.executeExecuteAdl(message, clusterTimestamp);
            case RESOLVE_LIQUIDATION -> liquidations.executeResolveLiquidation(message, clusterTimestamp);
            case CONTINUE_RISK_SCAN -> risk.executeContinueRiskScan(message, clusterTimestamp);
            case UPDATE_RISK_SCAN_CONTROL -> risk.executeUpdateRiskScanControl(message, clusterTimestamp);
            case UPDATE_INSTRUMENT_MAINTENANCE -> instruments.executeUpdateInstrumentMaintenance(message, clusterTimestamp);
            case UPSERT_FEE_POLICY -> fees.executeUpsertFeePolicy(message, clusterTimestamp);
            case UPDATE_POSITION_MODE -> positions.executeUpdatePositionMode(message);
            case ADJUST_POSITION_MARGIN -> positions.executeAdjustPositionMargin(message);
            case ADJUST_INSURANCE_FUND -> insurance.executeAdjustInsuranceFund(message, clusterTimestamp);
            case UPDATE_LEVERAGE -> leverage.executeUpdateLeverage(message, clusterTimestamp);
            case UPSERT_ALGO_ORDER -> triggers.executeUpsertAlgoOrder(message, clusterTimestamp);
            case UPDATE_CANCEL_ALL_AFTER -> triggers.executeUpdateCancelAllAfter(message, clusterTimestamp);
            case PLACE_TRIGGER_OCO_PAIR -> triggers.executePlaceTriggerOcoPair(message, clusterTimestamp);
            case PLACE_TRIGGER_ORDER -> triggers.executePlaceTriggerOrder(message, clusterTimestamp);
            case CANCEL_TRIGGER_ORDER -> triggers.executeCancelTriggerOrder(message, clusterTimestamp);
            case CLAIM_TRIGGER_ORDER -> triggers.executeClaimTriggerOrder(message, clusterTimestamp);
            case COMPLETE_TRIGGER_ORDER -> triggers.executeCompleteTriggerOrder(message, clusterTimestamp);
            case UPDATE_TRIGGER_TRAILING -> triggers.executeUpdateTriggerTrailing(message, clusterTimestamp);
            case EXPIRE_TRIGGER_ORDER -> triggers.executeExpireTriggerOrder(message, clusterTimestamp);
            case RETRY_TRIGGER_ORDER -> triggers.executeRetryTriggerOrder(message, clusterTimestamp);
            case EXECUTE_TRIGGER_ORDER -> triggers.executeExecuteTriggerOrder(message, clusterTimestamp);
            default -> { return false; }
        }
        return true;
    }
}
