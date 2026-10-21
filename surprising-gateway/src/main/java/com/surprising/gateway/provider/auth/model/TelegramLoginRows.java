package com.surprising.gateway.provider.auth.model;

/** Telegram流水的回调领取信息与授权结果；不作为HTTP响应。 */
public final class TelegramLoginRows {
    private TelegramLoginRows() { }
    public record Flow(String nonce,String codeVerifier,boolean returnToApp) { }
    public record Result(String subject,String displayName,boolean failed) { }
}
