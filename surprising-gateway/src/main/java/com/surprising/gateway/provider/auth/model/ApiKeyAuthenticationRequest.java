package com.surprising.gateway.provider.auth.model;

/** HTTP绑定后的精确时间戳、窗口与原始签名载荷；不携带Servlet请求。 */
public record ApiKeyAuthenticationRequest(String apiKey, String ipAddress, long timestamp,
                                           long recvWindow, String signature, String canonical) {
}
