package com.surprising.trading.trigger.model;

/** Controller已绑定的活动查询；beforeId=0表示首屏，不限制合法int64上界。 */
public record OpenTriggerPageQuery(int limit, long beforeId) {
}
