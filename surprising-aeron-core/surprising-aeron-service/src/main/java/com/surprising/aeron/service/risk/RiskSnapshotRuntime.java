package com.surprising.aeron.service.risk;

import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.CorePositionSide;

/**
 * 账户 Lane 计算出的单份风险结果，用整数币对编号定位用户持仓腿，保存对应行情序号、权益、盈亏和强平风险结论。
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param symbolId 运行时使用的非负币对整数编号；对外币对编号需要通过身份表换回
 * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
 * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
 * @param equityUnits 本次风险判断采用的权益金额，逐仓看该仓权益，全仓看共享权益，单位为结算资产最小单位
 * @param unrealizedPnlUnits 按当前风险价格计算的未实现盈亏，单位为结算资产最小单位
 * @param maintenanceMarginUnits 维持持仓所要求的保证金金额，单位为结算资产最小单位
 * @param marginRatioPpm 维持保证金相对权益的风险占比，按一百万倍保存，越大越接近强平
 * @param status 这次估值的正常、预警或强平结论
 */
public record RiskSnapshotRuntime(long userId, int symbolId, CorePositionSide positionSide,
                                  long priceSequence, long equityUnits, long unrealizedPnlUnits,
                                  long maintenanceMarginUnits, long marginRatioPpm, CoreRiskStatus status) {
    /**
     * 检查用户、币对整数编号、持仓腿和价格序号有效，维持保证金与风险占比非负，风险结论不能为空。
     */
    public RiskSnapshotRuntime {
        if (userId <= 0 || symbolId < 0 || positionSide == null || priceSequence <= 0
                || maintenanceMarginUnits < 0 || marginRatioPpm < 0 || status == null) {
            throw new IllegalArgumentException("invalid runtime risk snapshot");
        }
    }
}
