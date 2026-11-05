package com.surprising.gateway.provider.telegram.bot.model.keyboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramKeyboardButton(
        @JsonProperty("text") String text,
        @JsonProperty("web_app") TelegramWebAppInfo webApp
) {
    public static TelegramKeyboardButton text(String text) {
        return new TelegramKeyboardButton(text, null);
    }

    public static TelegramKeyboardButton webApp(String text, String webAppUrl) {
        return new TelegramKeyboardButton(text, new TelegramWebAppInfo(webAppUrl));
    }
}
