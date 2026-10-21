package com.surprising.trading.trigger.model;

/** 已由请求入口验证的条件单历史坐标；SQL不读取原始游标字符串。 */
public record TriggerReadCursor(long createdAt, long triggerOrderId) {}
