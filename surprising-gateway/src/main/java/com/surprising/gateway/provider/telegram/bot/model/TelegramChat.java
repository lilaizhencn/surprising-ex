package com.surprising.gateway.provider.telegram.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramChat(
        @JsonProperty("id") long id,
        @JsonProperty("type") String type,
        @JsonProperty("title") String title,
        @JsonProperty("username") String username
) {}
