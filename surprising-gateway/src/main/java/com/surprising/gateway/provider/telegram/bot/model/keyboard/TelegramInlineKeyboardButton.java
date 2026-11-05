package com.surprising.gateway.provider.telegram.bot.model.keyboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramInlineKeyboardButton(
        @JsonProperty("text") String text,
        @JsonProperty("callback_data") String callbackData,
        @JsonProperty("url") String url,
        @JsonProperty("web_app") TelegramWebAppInfo webApp
) {
    public static TelegramInlineKeyboardButton callback(String text, String callbackData) {
        return new TelegramInlineKeyboardButton(text, callbackData, null, null);
    }

    public static TelegramInlineKeyboardButton url(String text, String url) {
        return new TelegramInlineKeyboardButton(text, null, url, null);
    }

    public static TelegramInlineKeyboardButton webApp(String text, String webAppUrl) {
        return new TelegramInlineKeyboardButton(text, null, null, new TelegramWebAppInfo(webAppUrl));
    }
}
