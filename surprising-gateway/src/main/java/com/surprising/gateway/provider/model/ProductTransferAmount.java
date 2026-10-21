package com.surprising.gateway.provider.model;

/** 已由请求入口解析的资产和精确原子单位，供安全估值使用。 */
public record ProductTransferAmount(String asset, long amountUnits) {}
