package com.surprising.gateway.provider.telegram.bot.service;

import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramI18nService {

    private static final Logger log = LoggerFactory.getLogger(TelegramI18nService.class);

    public record SupportedLanguage(String code, String displayName, Locale locale) {}

    public static final List<SupportedLanguage> SUPPORTED_LANGUAGES = List.of(
            new SupportedLanguage("zh_CN", "🇨🇳 简体中文", Locale.SIMPLIFIED_CHINESE),
            new SupportedLanguage("en", "🇺🇸 English", Locale.ENGLISH),
            new SupportedLanguage("ru", "🇷🇺 Русский", Locale.forLanguageTag("ru"))
    );

    private final ResourceBundleMessageSource messageSource;
    private final TelegramBotSecurityService securityService;
    private final Map<Long, Locale> preferredLocaleCache = new ConcurrentHashMap<>();

    public TelegramI18nService(TelegramBotSecurityService securityService) {
        this.securityService = securityService;
        this.messageSource = new ResourceBundleMessageSource();
        this.messageSource.setBasenames("i18n/telegram_messages");
        this.messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        this.messageSource.setFallbackToSystemLocale(false);
        this.messageSource.setDefaultLocale(Locale.ENGLISH);
    }

    /**
     * 解析 Telegram 用户的当前 Locale
     * 优先级：用户手动设置的偏好 > Telegram 客户端上报的 languageCode > 系统默认 (English)
     */
    public Locale resolveLocale(TelegramUser tgUser) {
        if (tgUser == null) {
            return Locale.ENGLISH;
        }

        // 1. 内存缓存中是否有手动设置的 Locale
        Locale cached = preferredLocaleCache.get(tgUser.id());
        if (cached != null) {
            return cached;
        }

        // 2. 根据 Telegram 客户端自带的 language_code 推断
        Locale detected = detectLocaleFromCode(tgUser.languageCode());
        return detected != null ? detected : Locale.ENGLISH;
    }

    /**
     * 从持久化配置初始化或刷新用户的语言偏好（若用户数据库中已有记录）
     */
    public void primeUserLocale(long telegramId, String preferredLangCode) {
        if (preferredLangCode != null && !preferredLangCode.isBlank()) {
            Locale loc = parseSupportedLocale(preferredLangCode);
            if (loc != null) {
                preferredLocaleCache.put(telegramId, loc);
            }
        }
    }

    /**
     * 设置用户首选语言并持久化
     */
    public Locale setUserLocale(long telegramId, long userId, String langTag) {
        Locale locale = parseSupportedLocale(langTag);
        if (locale == null) {
            locale = Locale.ENGLISH;
        }
        preferredLocaleCache.put(telegramId, locale);

        try {
            if (securityService != null && userId > 0) {
                securityService.updatePreferredLanguage(userId, toLanguageTag(locale));
            }
        } catch (Exception ex) {
            log.warn("Failed to persist user telegram language preference: userId={}, error={}", userId, ex.getMessage());
        }
        return locale;
    }

    /**
     * 翻译指定 key 文本
     */
    public String t(String key, Locale locale, Object... args) {
        Locale target = (locale != null) ? locale : Locale.ENGLISH;
        Object[] sanitizedArgs = sanitizeArgs(args);
        try {
            String msg = messageSource.getMessage(key, sanitizedArgs, target);
            if (msg != null) {
                return msg;
            }
        } catch (Exception ex) {
            // fallback
        }
        // 如果 key 不存在，简单做占位符替换或返回 key
        if (sanitizedArgs != null && sanitizedArgs.length > 0) {
            try {
                return MessageFormat.format(key, sanitizedArgs);
            } catch (Exception ignored) {}
        }
        return key;
    }

    private static Object[] sanitizeArgs(Object[] args) {
        if (args == null || args.length == 0) return args;
        Object[] copy = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Long || a instanceof Integer) {
                copy[i] = String.valueOf(a);
            } else {
                copy[i] = a;
            }
        }
        return copy;
    }

    /**
     * 快捷翻译：直接传入 TelegramUser 自动判定其语言
     */
    public String t(String key, TelegramUser tgUser, Object... args) {
        return t(key, resolveLocale(tgUser), args);
    }

    public List<SupportedLanguage> getSupportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    public static Locale detectLocaleFromCode(String code) {
        if (code == null || code.isBlank()) {
            return Locale.ENGLISH;
        }
        String normalized = code.toLowerCase(Locale.ROOT).trim();
        if (normalized.startsWith("zh")) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if (normalized.startsWith("ru")) {
            return Locale.forLanguageTag("ru");
        }
        if (normalized.startsWith("en")) {
            return Locale.ENGLISH;
        }
        return Locale.ENGLISH;
    }

    public static Locale parseSupportedLocale(String langTag) {
        if (langTag == null) return null;
        String normalized = langTag.toLowerCase(Locale.ROOT).replace("-", "_").trim();
        if (normalized.startsWith("zh")) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if (normalized.startsWith("ru")) {
            return Locale.forLanguageTag("ru");
        }
        if (normalized.startsWith("en")) {
            return Locale.ENGLISH;
        }
        return Locale.ENGLISH;
    }

    public static String toLanguageTag(Locale locale) {
        if (locale == null) return "en";
        if (Locale.SIMPLIFIED_CHINESE.equals(locale) || locale.getLanguage().equalsIgnoreCase("zh")) {
            return "zh_CN";
        }
        if (locale.getLanguage().equalsIgnoreCase("ru")) {
            return "ru";
        }
        return "en";
    }

    public static String getLanguageDisplayName(Locale locale) {
        for (SupportedLanguage lang : SUPPORTED_LANGUAGES) {
            if (lang.locale().getLanguage().equalsIgnoreCase(locale.getLanguage())) {
                return lang.displayName();
            }
        }
        return locale.getDisplayLanguage();
    }
}
