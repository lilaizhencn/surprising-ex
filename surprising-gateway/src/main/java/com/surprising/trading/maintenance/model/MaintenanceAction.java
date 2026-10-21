package com.surprising.trading.maintenance.model;

/** 持久命令身份与不可变请求，未知结果重试必须继续使用同一份请求。 */
public record MaintenanceAction(String key, String requestJson, String resultJson, boolean completed) { }
