package com.surprising.gateway.provider.telegram.bot.handler;

import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.repository.TelegramIdentityRepository;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

@Component
public class TelegramAuthHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthHandler.class);

    private final TelegramIdentityRepository identities;
    private final AuthPersistenceService authPersistence;
    private final GatewayUserRepository users;
    private final TelegramBotClient botClient;
    private final TelegramBotProperties properties;
    private final TelegramI18nService i18n;
    private final TelegramBotSecurityService securityService;

    public TelegramAuthHandler(TelegramIdentityRepository identities,
                               AuthPersistenceService authPersistence,
                               GatewayUserRepository users,
                               TelegramBotClient botClient,
                               TelegramBotProperties properties,
                               TelegramI18nService i18n,
                               TelegramBotSecurityService securityService) {
        this.identities = identities;
        this.authPersistence = authPersistence;
        this.users = users;
        this.botClient = botClient;
        this.properties = properties;
        this.i18n = i18n;
        this.securityService = securityService;
    }

    public long resolveUserId(TelegramUser tgUser) {
        String subject = String.valueOf(tgUser.id());
        Instant now = Instant.now();
        long userId = identities.lockIdentity(subject).orElseGet(() -> {
            log.info("New Telegram user auto-registration: id={}, name={}", tgUser.id(), tgUser.getDisplayName());
            var user = authPersistence.createUser(null, null, null, now);
            authPersistence.ensureDefaultRole(user.userId(), now);
            identities.bind(subject, user.userId(), now);
            users.updateNickname(user.userId(), tgUser.getDisplayName(), now);
            return user.userId();
        });

        // 尝试加载用户保存的偏好语言以热身 i18n 缓存
        try {
            if (securityService != null) {
                TelegramSecuritySettings settings = securityService.getSettings(userId);
                if (settings != null && settings.preferredLanguage() != null) {
                    i18n.primeUserLocale(tgUser.id(), settings.preferredLanguage());
                }
            }
        } catch (Exception ignored) {}

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
