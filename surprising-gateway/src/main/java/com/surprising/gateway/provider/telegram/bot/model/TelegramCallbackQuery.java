package com.surprising.gateway.provider.telegram.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramCallbackQuery(
        @JsonProperty("id") String id,
        @JsonProperty("from") TelegramUser from,
        @JsonProperty("message") TelegramMessage message,
        @JsonProperty("data") String data
) {}
