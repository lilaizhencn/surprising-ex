package com.surprising.gateway.provider.telegram.bot.model.keyboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramInlineKeyboardMarkup(
        @JsonProperty("inline_keyboard") List<List<TelegramInlineKeyboardButton>> inlineKeyboard
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<List<TelegramInlineKeyboardButton>> rows = new ArrayList<>();

        public Builder addRow(TelegramInlineKeyboardButton... buttons) {
            rows.add(List.of(buttons));
            return this;
        }

        public Builder addRow(List<TelegramInlineKeyboardButton> buttons) {
            rows.add(new ArrayList<>(buttons));
            return this;
        }

        public TelegramInlineKeyboardMarkup build() {
            return new TelegramInlineKeyboardMarkup(rows);
        }
    }
}
