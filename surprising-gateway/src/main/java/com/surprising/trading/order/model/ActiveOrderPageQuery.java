package com.surprising.trading.order.model;

/** Controller已绑定的活动查询；beforeId=0表示首屏，不限制合法int64上界。 */
public record ActiveOrderPageQuery(int limit, long beforeId) {
}
