package com.surprising.gateway.provider.auth.port;

import com.surprising.gateway.provider.auth.model.AuthClientMetadata;

/** 鉴权读取调用方元数据并通知入口扣减已认证请求额度；沿用已有请求上下文。 */
public interface CurrentAuthClient {
    AuthClientMetadata current();
    void authenticated(long userId, String apiKey);
    void loginAttempt(String identifier);
}
