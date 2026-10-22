package com.surprising.aeron.service.order.command;

import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.matcher.CoreMatchingOrder;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;

/**
 * 保存改单准入解析结果及后续核对所需的原订单、账户修订。新单协议意图、业务解析结果、可选 Matcher 输入和所需预留一并交接；记录保留对象引用，不自行复制或授予跨线程账户读取权限。
 * @param userId 本次准入所属用户，必须为正
 * @param originalOrderId 需要替换或修改的原订单号，必须为正
 * @param originalOrderRevision 准入时观察的原订单修订，后续核对是否被其他工作改变
 * @param userRevision 准入时观察的账户修订，后续用于一致性核对
 * @param command 本次新单协议意图，订单号必须与 resolved 一致
 * @param resolved 已经完成业务参数解析的新单结果，仍按原生命周期持有引用
 * @param matchingOrder 可空的 Matcher 输入，存在时订单号必须与 resolved 一致
 * @param requiredReservationUnits 新单需要的资金预留单位数，不能为负
 */
public record ResolvedMatchingAdmission(long userId, long originalOrderId, long originalOrderRevision,
                                 long userRevision, PlaceOrderCommand command,
                                 ResolvedPlaceOrder resolved, CoreMatchingOrder matchingOrder,
                                 long requiredReservationUnits) {

    /**
     * 检查用户、原订单身份及非负修订和预留，并核对新单意图、业务解析结果及可选 Matcher 输入的订单号一致；不在这里执行完整产品或账户业务校验。
     */
    public ResolvedMatchingAdmission {
        if (userId <= 0 || originalOrderId <= 0 || originalOrderRevision < 0 || userRevision < 0
                || command == null || resolved == null
                || requiredReservationUnits < 0 || command.orderId() != resolved.orderId()
                || (matchingOrder != null && matchingOrder.orderId() != resolved.orderId())) {
            throw new IllegalArgumentException("invalid resolved matching admission");
        }
    }

    /**
     * 提供尚无 Matcher 输入的准入结果，其他校验沿用主构造入口。
     * @param userId 本次准入所属用户，必须为正
     * @param originalOrderId 需要替换或修改的原订单号，必须为正
     * @param originalOrderRevision 准入时观察的原订单修订，后续核对是否被其他工作改变
     * @param userRevision 准入时观察的账户修订，后续用于一致性核对
     * @param command 本次新单协议意图，订单号必须与 resolved 一致
     * @param resolved 已经完成业务参数解析的新单结果，仍按原生命周期持有引用
     * @param requiredReservationUnits 新单需要的资金预留单位数，不能为负
     */
    public ResolvedMatchingAdmission(long userId, long originalOrderId, long originalOrderRevision,
                                     long userRevision, PlaceOrderCommand command,
                                     ResolvedPlaceOrder resolved,
                                     long requiredReservationUnits) {
        this(userId, originalOrderId, originalOrderRevision, userRevision, command, resolved, null, requiredReservationUnits);
    }
}
