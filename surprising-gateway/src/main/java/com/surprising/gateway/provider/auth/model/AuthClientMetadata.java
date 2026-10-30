package com.surprising.gateway.provider.auth.model;

/** HTTP入口完成绑定的客户端事实，不携带Servlet请求或可变请求上下文。 */
public record AuthClientMetadata(String userAgent, String ipAddress, String deviceId,
                                java.util.List<com.surprising.gateway.provider.legal.model.LegalModels.Acceptance> acknowledgements,
                                boolean administrativeClient) {
    public AuthClientMetadata { acknowledgements=java.util.List.copyOf(acknowledgements); }
}
