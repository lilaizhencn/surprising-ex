package com.surprising.aeron.service.query;

/** 本次待完成划转页的固定编号、用户路由和 DTO 槽，用于各 Lane 原位读取，不保存第二份划转状态。 */
record PendingTransferQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.TransferFundsCommand[] views) { }
