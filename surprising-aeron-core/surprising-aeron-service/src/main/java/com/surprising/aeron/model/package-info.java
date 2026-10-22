/**
 * 交易状态值、恢复导出数据与跨启动/服务层的日志元数据。
 * 这些数据不承担业务路由；在线可变账户、订单和持仓仍归相应业务服务及所属线程所有。
 * 快照格式由 service.snapshot 的显式编解码器定义，不依赖 Java 类名序列化。
 */
package com.surprising.aeron.model;
