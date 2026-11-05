package com.surprising.gateway.provider.telegram.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(
        @JsonProperty("update_id") long updateId,
        @JsonProperty("message") TelegramMessage message,
        @JsonProperty("callback_query") TelegramCallbackQuery callbackQuery
) {
    public Long getFromId() {
        if (message != null && message.from() != null) {
            return message.from().id();
        }
        if (callbackQuery != null && callbackQuery.from() != null) {
            return callbackQuery.from().id();
        }
        return null;
    }

    public Long getChatId() {
        if (message != null && message.chat() != null) {
            return message.chat().id();
        }
        if (callbackQuery != null && callbackQuery.message() != null && callbackQuery.message().chat() != null) {
            return callbackQuery.message().chat().id();
        }
        return null;
    }
}
