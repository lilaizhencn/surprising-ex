package com.surprising.gateway.provider.auth;

/** 短信供应商边界；没有配置实现时不得绑定或跳过手机验证。 */
public interface SmsMessageSender {
    void send(String phone, String code);
}
