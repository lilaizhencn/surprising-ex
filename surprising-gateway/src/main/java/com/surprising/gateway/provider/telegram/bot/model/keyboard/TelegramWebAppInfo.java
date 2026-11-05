package com.surprising.gateway.provider.telegram.bot.model.keyboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramWebAppInfo(
        @JsonProperty("url") String url
) {}
