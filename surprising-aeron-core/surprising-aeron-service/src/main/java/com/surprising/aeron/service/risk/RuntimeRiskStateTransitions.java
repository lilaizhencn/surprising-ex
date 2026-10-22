package com.surprising.aeron.service.risk;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 更新运行时风险扫描控制和扫描游标。控制配置用版本号防止旧配置覆盖新配置，游标替换保留恢复所需进度，每次成功更新都推进命令修订号。 构造约定：只提供状态更新步骤，不额外持有扫描状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeRiskStateTransitions {

    /**
     * Owner 校验期望控制版本，生成下一版本的启用、间隔和工作量配置；更新时间最低取 0，写入后增加命令修订号。
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param command 带期望版本、扫描开关、间隔、工作量和操作原因的控制更新命令
     * @param updatedAtEpochMillis 本次控制更新的 Unix 毫秒时间戳，负值会归为 0
     */
    public static void updateScanControl(TradingRuntimeState runtime, UpdateRiskScanControlCommand command,
                                  long updatedAtEpochMillis) {
        if (runtime == null || command == null) {
            throw new IllegalArgumentException("invalid runtime risk scan control update");
        }
        runtime.assertOwner();
        // 运行时当前风险扫描控制配置，新命令的期望版本必须与它一致。
        CoreRiskScanControlView current = runtime.riskScanControl();
        if (command.expectedVersion() != current.version()) {
            throw new CoreStateRejectedException("STALE_RISK_SCAN_CONTROL_VERSION",
                    "risk scan control version does not match");
        }
        runtime.requireRevisionCapacity();
        runtime.setRiskScanControl(new CoreRiskScanControlView(
                Math.incrementExact(current.version()), command.ruleName(), command.enabled(),
                command.scanDelayMs(), command.scanBatchSize(), command.adminUserId(), command.reason(),
                Math.max(0, updatedAtEpochMillis)));
        runtime.incrementCommandRevision();
    }

    /**
     * 把业务扫描记录的外部币对编号换成运行时整数编号，连同全部 Lane 和条件单游标写回，再增加命令修订号。
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和持仓
     * @param scan 包含账户风险及条件单游标的原扫描记录
     */
    public static void replaceScan(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                            CoreRiskState.RiskScan scan) {
        if (runtime == null || identities == null || scan == null) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
        runtime.putRiskScan(new RiskScanRuntime(identities.symbolId(scan.instrumentId()), scan.accountLaneId(),
                scan.priceSequence(), scan.scanStartPriceSequence(), scan.lastUserId(),
                scan.riskComplete(), scan.riskUserId(), scan.riskPhase(), scan.riskPositionCursor(),
                scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(),
                scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor(), scan.lastScheduledRevision(), scan.laneProgress()));
        runtime.incrementCommandRevision();
    }

    /**
     * 直接写入已使用整数币对编号的运行时扫描记录，再增加命令修订号。
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param scan 包含账户风险及条件单游标的原扫描记录
     */
    public static void replaceScan(TradingRuntimeState runtime, RiskScanRuntime scan) {
        if (runtime == null || scan == null) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
        runtime.putRiskScan(scan);
        runtime.incrementCommandRevision();
    }
}
