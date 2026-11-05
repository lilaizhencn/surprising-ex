package com.surprising.gateway.provider.telegram.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(
        @JsonProperty("message_id") long messageId,
        @JsonProperty("from") TelegramUser from,
        @JsonProperty("chat") TelegramChat chat,
        @JsonProperty("date") Long date,
        @JsonProperty("text") String text
) {}
