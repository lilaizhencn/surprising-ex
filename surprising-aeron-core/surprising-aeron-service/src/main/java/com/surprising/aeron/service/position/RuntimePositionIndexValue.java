package com.surprising.aeron.service.position;

import com.surprising.aeron.protocol.CorePositionSide;

/**
 * 持仓发布给业务索引时使用的精简字段，只携带用户、币对、结算资产、持仓腿及数量，供索引维护身份和成员关系；不包含账户余额、保证金或估值结果。
 * @param userId 本次发布持仓所属的用户编号
 * @param instrumentId 持仓对应的外部合约编号
 * @param asset 持仓的保证金或结算资产代码，业务索引按它分组
 * @param positionSide 净持仓或双向持仓腿身份
 * @param signedQuantitySteps 带方向持仓数量步数，正数多头、负数空头，0 表示空仓成员应移除
 */
public record RuntimePositionIndexValue(long userId, String instrumentId, String asset,
                                 CorePositionSide positionSide, long signedQuantitySteps) {

}
