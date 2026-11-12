package com.surprising.aeron.service.query;

/** 本次触发单页的固定编号、用户路由和响应槽。各 Lane 只填自己的下标，全部完成后按原选择顺序编码。 */
record TriggerQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CoreTriggerOrderStateView[] views) { }
