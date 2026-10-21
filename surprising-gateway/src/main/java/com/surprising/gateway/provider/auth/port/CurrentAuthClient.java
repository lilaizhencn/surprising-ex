package com.surprising.gateway.provider.auth.port;

import com.surprising.gateway.provider.auth.model.AuthClientMetadata;

/** 已有请求上下文的原生读取端口；非HTTP调用返回null，不新建ThreadLocal或第二份请求状态。 */
@FunctionalInterface
public interface CurrentAuthClient {
    AuthClientMetadata current();
}
