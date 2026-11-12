package com.surprising.aeron.service.query;

/** 本次算法父单页的固定路由与响应槽，各 Lane 汇总自己的父单及保留子单，最后合并时再检查全局子单扫描量。 */
record AlgoQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CoreAlgoOrderView[] views) { }
