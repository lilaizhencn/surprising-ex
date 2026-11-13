package com.surprising.gateway.provider.auth.state;

/** Valkey 读写失败，且进程内没有可用的登录态；按"登录无效"处理，调用方会返回 401。 */
public class AuthStateUnavailableException extends IllegalArgumentException {
    public AuthStateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
