package com.surprising.gateway.provider.model;

/** Controller从本机请求绑定的审计事实；不携带Servlet或持久化对象。 */
public record LiquidityOperationMetadata(String reference,String reason,String requestPath,String clientIp,String bodyHash) {}
