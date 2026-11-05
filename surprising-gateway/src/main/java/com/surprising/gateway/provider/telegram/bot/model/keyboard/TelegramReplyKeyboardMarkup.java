package com.surprising.gateway.provider.telegram.bot.model.keyboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramReplyKeyboardMarkup(
        @JsonProperty("keyboard") List<List<TelegramKeyboardButton>> keyboard,
        @JsonProperty("resize_keyboard") boolean resizeKeyboard,
        @JsonProperty("one_time_keyboard") boolean oneTimeKeyboard,
        @JsonProperty("is_persistent") boolean isPersistent
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<List<TelegramKeyboardButton>> rows = new ArrayList<>();
        private boolean resizeKeyboard = true;
        private boolean oneTimeKeyboard = false;
        private boolean isPersistent = true;

        public Builder addRow(TelegramKeyboardButton... buttons) {
            rows.add(List.of(buttons));
            return this;
        }

        public Builder addRow(List<TelegramKeyboardButton> buttons) {
            rows.add(new ArrayList<>(buttons));
            return this;
        }

        public Builder resizeKeyboard(boolean resize) {
            this.resizeKeyboard = resize;
            return this;
        }

        public Builder oneTimeKeyboard(boolean oneTime) {
            this.oneTimeKeyboard = oneTime;
            return this;
        }

        public Builder isPersistent(boolean persistent) {
            this.isPersistent = persistent;
            return this;
        }

        public TelegramReplyKeyboardMarkup build() {
            return new TelegramReplyKeyboardMarkup(rows, resizeKeyboard, oneTimeKeyboard, isPersistent);
        }
    }
}
