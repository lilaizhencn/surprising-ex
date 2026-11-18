package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.gateway.provider.auth.service.TelegramIdentityService;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecuritySettings;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramKeyboardButton;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramReplyKeyboardMarkup;
import com.surprising.gateway.provider.telegram.bot.service.TelegramBotSecurityService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramI18nService;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class TelegramAuthHandler {

    private final TelegramIdentityService identities;
    private final TelegramBotClient botClient;
    private final TelegramBotProperties properties;
    private final TelegramI18nService i18n;
    private final TelegramBotSecurityService securityService;
    private final java.util.concurrent.ConcurrentHashMap<Long,Long> languageLoadedAt=new java.util.concurrent.ConcurrentHashMap<>();

    public TelegramAuthHandler(TelegramIdentityService identities,
                               TelegramBotClient botClient,
                               TelegramBotProperties properties,
                               TelegramI18nService i18n,
                               TelegramBotSecurityService securityService) {
        this.identities = identities;
        this.botClient = botClient;
        this.properties = properties;
        this.i18n = i18n;
        this.securityService = securityService;
    }

    public long registerUser(TelegramUser user) {
        return identities.registerOrLogin(String.valueOf(user.id()), user.getDisplayName());
    }

    public long resolveUserId(TelegramUser tgUser) {
        long userId = identities.resolve(String.valueOf(tgUser.id()), tgUser.getDisplayName());

        long now=System.nanoTime();
        languageLoadedAt.compute(tgUser.id(),(id,loadedAt)->{
            if(loadedAt!=null && now-loadedAt<60_000_000_000L) return loadedAt;
            try {
                if(securityService!=null) {
                    var settings=securityService.getSettings(userId);
                    if(settings!=null) i18n.primeUserLocale(id,settings.preferredLanguage());
                }
            } catch(RuntimeException unavailable) {
                // 语言偏好属于业务设置，失败不改变已验证的身份。
            }
            return now;
        });
        if(languageLoadedAt.size()>100_000) languageLoadedAt.clear();
        return userId;
    }

    public void sendWelcomeAndMainMenu(long chatId, TelegramUser tgUser) {
        long userId = resolveUserId(tgUser);
        Locale locale = i18n.resolveLocale(tgUser);

        String text = i18n.t("menu.welcome", locale) + "\n\n" +
                i18n.t("menu.current_user", locale, escapeHtml(tgUser.getDisplayName())) + "\n" +
                i18n.t("menu.account_uid", locale, userId) + "\n" +
                i18n.t("menu.telegram_id", locale, tgUser.id()) + "\n\n" +
                i18n.t("menu.intro", locale);

        TelegramReplyKeyboardMarkup replyMarkup = buildReplyKeyboard(locale);
        TelegramInlineKeyboardMarkup inlineMarkup = buildInlineKeyboard(locale);

        botClient.sendMessage(chatId, text, inlineMarkup);
        botClient.sendMessage(chatId, i18n.t("menu.bottom_nav_hint", locale), replyMarkup);
    }

    public void renderMainMenu(long chatId, Long messageId, TelegramUser tgUser) {
        long userId = resolveUserId(tgUser);
        Locale locale = i18n.resolveLocale(tgUser);

        String text = i18n.t("menu.welcome", locale) + "\n\n" +
                i18n.t("menu.current_user", locale, escapeHtml(tgUser.getDisplayName())) + " (" +
                i18n.t("menu.account_uid", locale, userId) + ")\n\n" +
                i18n.t("menu.intro", locale);

        TelegramInlineKeyboardMarkup inlineMarkup = buildInlineKeyboard(locale);

        if (messageId != null) {
            botClient.editMessageText(chatId, messageId, text, inlineMarkup);
        } else {
            botClient.sendMessage(chatId, text, inlineMarkup);
        }
    }

    public TelegramReplyKeyboardMarkup buildReplyKeyboard(Locale locale) {
        return TelegramReplyKeyboardMarkup.builder()
                .addRow(
                        TelegramKeyboardButton.text(i18n.t("menu.btn.assets", locale)),
                        TelegramKeyboardButton.text(i18n.t("menu.btn.market", locale))
                )
                .addRow(
                        TelegramKeyboardButton.text(i18n.t("menu.btn.positions", locale)),
                        TelegramKeyboardButton.text(i18n.t("menu.btn.orders", locale)),
                        TelegramKeyboardButton.text(i18n.t("menu.btn.trade", locale))
                )
                .addRow(
                        TelegramKeyboardButton.text(i18n.t("menu.btn.deposit", locale)),
                        TelegramKeyboardButton.text(i18n.t("menu.btn.withdraw", locale)),
                        TelegramKeyboardButton.text(i18n.t("menu.btn.transfer", locale))
                )
                .addRow(
                        TelegramKeyboardButton.text(i18n.t("menu.btn.kol", locale)),
                        TelegramKeyboardButton.webApp(i18n.t("menu.open_miniapp_btn", locale), properties.getWebappUrl())
                )
                .build();
    }

    public TelegramInlineKeyboardMarkup buildInlineKeyboard(Locale locale) {
        return TelegramInlineKeyboardMarkup.builder()
                .addRow(
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.assets", locale), "menu:assets"),
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.positions", locale), "menu:positions")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.market", locale), "menu:market"),
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.orders", locale), "menu:orders"),
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.trade", locale), "menu:trade")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.deposit", locale), "menu:deposit"),
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.withdraw", locale), "menu:withdraw"),
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.transfer", locale), "menu:transfer")
                )
                .addRow(
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.kol", locale), "kol:dashboard"),
                        TelegramInlineKeyboardButton.callback(i18n.t("menu.btn.settings", locale), "menu:settings")
                )
                .addRow(
                        TelegramInlineKeyboardButton.webApp(i18n.t("menu.open_miniapp_btn", locale), properties.getWebappUrl())
                )
                .build();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
