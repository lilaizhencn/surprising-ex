package com.surprising.aeron.service.order;

import com.surprising.aeron.service.account.AccountPartition;
import org.agrona.collections.Long2LongHashMap;

/** 客户订单号与内部订单号在 AccountPartition 中的双向索引操作。 */
public final class ClientOrderIndexOps {

    private ClientOrderIndexOps() {}

    /** 更新客户号到订单及订单到客户号双向索引。 */
    public static void putClientOrderIndex(AccountPartition lane, long userId, long clientKey, long orderId) {
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (userClientOrders == null) {
            userClientOrders = new Long2LongHashMap(lane.orderCheckIndexCapacity(), 0.65f, 0, false);
            lane.storeUserClientOrders(userId, userClientOrders);
        }
        long previousOrderId = userClientOrders.get(clientKey);
        boolean hadPrevious = previousOrderId != 0;
        userClientOrders.put(clientKey, orderId);
        if (hadPrevious && previousOrderId != orderId) {
            lane.clientKeysByOrderId().remove(previousOrderId, clientKey);
        }
        lane.clientKeysByOrderId().add(orderId, clientKey);
    }

    /** 客户号存在时删除正向及反向关系并返回原订单号，未找到返回空。 */
    public static Long removeClientOrderIndex(AccountPartition lane, long userId, long clientKey) {
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (userClientOrders == null) return null;
        long orderId = userClientOrders.get(clientKey);
        if (orderId == 0) return null;
        userClientOrders.remove(clientKey);
        lane.clientKeysByOrderId().remove(orderId, clientKey);
        return orderId;
    }
}
