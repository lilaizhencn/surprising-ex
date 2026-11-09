package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecurityMode;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecuritySettings;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSessionState;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramReplyKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.service.TelegramBotSecurityService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramI18nService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramSessionService;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class TelegramSettingsHandler {

    private final TelegramAuthHandler authHandler;
    private final TelegramBotSecurityService securityService;
    private final TelegramSessionService sessionService;
    private final TelegramBotClient botClient;
    private final TelegramI18nService i18n;

    public TelegramSettingsHandler(TelegramAuthHandler authHandler,
                                   TelegramBotSecurityService securityService,
                                   TelegramSessionService sessionService,
                                   TelegramBotClient botClient,
                                   TelegramI18nService i18n) {
        this.authHandler = authHandler;
        this.securityService = securityService;
        this.sessionService = sessionService;
        this.botClient = botClient;
        this.i18n = i18n;
    }

    public void renderSettings(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = authHandler.resolveUserId(tgUser);
        TelegramSecuritySettings settings = securityService.getSettings(userId);
        Locale locale = i18n.resolveLocale(tgUser);

        String modeDesc = switch (settings.securityMode()) {
            case CONFIRM_ONLY -> i18n.t("settings.mode.confirm_only", locale);
            case TRADE_PASSWORD -> i18n.t("settings.mode.trade_password", locale);
            case OTP_VERIFY -> i18n.t("settings.mode.otp_verify", locale);
        };

        String pinDesc = settings.hasTradePin()
                ? i18n.t("settings.pin_set", locale)
                : i18n.t("settings.pin_unset", locale);

        String currentLangName = TelegramI18nService.getLanguageDisplayName(locale);

        String text = i18n.t("settings.title", locale) + "\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                i18n.t("settings.uid", locale, userId) + "\n" +
                i18n.t("settings.mode_label", locale) + "\n" + modeDesc + "\n" +
                i18n.t("settings.pin_status", locale) + " " + pinDesc + "\n" +
                "🌐 <b>" + i18n.t("settings.lang.current", locale, currentLangName) + "</b>\n\n" +
                i18n.t("settings.intro_hint", locale);

        TelegramInlineKeyboardMarkup.Builder markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback(i18n.t("settings.btn.set_confirm_only", locale), "settings:mode:CONFIRM_ONLY"),
                        TelegramInlineKeyboardButton.callback(i18n.t("settings.btn.set_trade_password", locale), "settings:mode:TRADE_PASSWORD")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback(i18n.t("settings.btn.set_otp_verify", locale), "settings:mode:OTP_VERIFY")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback(i18n.t("settings.btn.setup_pin", locale), "settings:begin_pin"),
                        TelegramInlineKeyboardButton.callback(i18n.t("settings.btn.lang_menu", locale), "settings:lang:menu")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.back", locale), "menu:main")
                );

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup.build());
        } else {
            botClient.sendMessage(chatId, text, markup.build());
        }
    }

    public void renderLanguageSettings(long chatId, Long messageId, TelegramUser tgUser) {
        Locale current = i18n.resolveLocale(tgUser);

        String text = i18n.t("settings.lang.title", current) + "\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                i18n.t("settings.lang.choose_prompt", current);

        TelegramInlineKeyboardMarkup.Builder markup = TelegramInlineKeyboardMarkup.builder();
        for (TelegramI18nService.SupportedLanguage lang : i18n.getSupportedLanguages()) {
            boolean isSelected = lang.locale().getLanguage().equalsIgnoreCase(current.getLanguage());
            String btnText = lang.displayName() + (isSelected ? " ✅" : "");
            markup.addRow(TelegramInlineKeyboardButton.callback(btnText, "settings:lang:set:" + lang.code()));
        }
        markup.addRow(TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.back", current), "menu:settings"));

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup.build());
        } else {
            botClient.sendMessage(chatId, text, markup.build());
        }
    }

    public void handleLanguageChange(long chatId, Long messageId, TelegramUser tgUser, String langTag) {
        long userId = authHandler.resolveUserId(tgUser);
        Locale newLocale = i18n.setUserLocale(tgUser.id(), userId, langTag);
        String langName = TelegramI18nService.getLanguageDisplayName(newLocale);

        // 答复点击提示
        botClient.answerCallbackQuery(null, i18n.t("settings.lang.success", newLocale, langName), false);

        // 重新渲染设置页
        renderSettings(chatId, messageId, tgUser);

        // 发送更新后的常驻底部 Reply 键盘
        TelegramReplyKeyboardMarkup replyMarkup = authHandler.buildReplyKeyboard(newLocale);
        botClient.sendMessage(chatId, i18n.t("settings.lang.success", newLocale, langName), replyMarkup);
    }

    public void handleModeChange(long chatId, long messageId, TelegramUser tgUser, String modeStr) {
        long userId = authHandler.resolveUserId(tgUser);
        TelegramSecurityMode mode = TelegramSecurityMode.fromString(modeStr);
        Locale locale = i18n.resolveLocale(tgUser);
        try {
            securityService.updateSecurityMode(userId, mode);
            renderSettings(chatId, messageId, tgUser);
        } catch (IllegalStateException ex) {
            botClient.answerCallbackQuery(null, ex.getMessage(), true);
            String text = "⚠️ <b>" + i18n.t("settings.pin_required_alert", locale) + "</b>\n\n" +
                    ex.getMessage();
            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback(i18n.t("settings.btn.setup_pin", locale), "settings:begin_pin"))
                    .addRow(TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.back", locale), "menu:settings"))
                    .build();
            botClient.editMessageText(chatId, messageId, text, markup);
        }
    }

    public void startPinSetupStep(long chatId, Long messageId, TelegramUser tgUser) {
        sessionService.setState(tgUser.id(), TelegramSessionState.Step.SETTINGS_AWAITING_NEW_PIN, null);
        Locale locale = i18n.resolveLocale(tgUser);

        String text = i18n.t("settings.pin_setup_title", locale) + "\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                i18n.t("settings.pin_setup_prompt", locale);

        TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                .addRow(TelegramInlineKeyboardButton.callback(i18n.t("common.btn.cancel", locale), "menu:settings"))
                .build();

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, markup);
        } else {
            botClient.sendMessage(chatId, text, markup);
        }
    }

    public void handlePinInput(long chatId, TelegramUser tgUser, String pinText) {
        long userId = authHandler.resolveUserId(tgUser);
        String pin = pinText.trim();
        Locale locale = i18n.resolveLocale(tgUser);
        try {
            securityService.setTradePin(userId, pin);
            sessionService.clearState(tgUser.id());

            String text = i18n.t("settings.pin_success", locale);
            TelegramInlineKeyboardMarkup markup = TelegramInlineKeyboardMarkup.builder()
                    .addRow(TelegramInlineKeyboardButton.callback(i18n.t("settings.btn.lang_menu", locale), "menu:settings"))
                    .addRow(TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.back", locale), "menu:main"))
                    .build();
            botClient.sendMessage(chatId, text, markup);
        } catch (IllegalArgumentException ex) {
            botClient.sendMessage(chatId, i18n.t("settings.pin_invalid", locale), null);
        }
    }
}
