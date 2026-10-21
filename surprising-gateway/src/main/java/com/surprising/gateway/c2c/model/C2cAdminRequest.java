package com.surprising.gateway.c2c.model;

/** 审批与审计所需的原始请求事实；由Controller从HTTP绑定，正文单独保持原始字节。 */
public record C2cAdminRequest(String approvalId, String method, String path, String query, String userAgent, String ip) {}
