package com.surprising.gateway.provider.content.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 公告和协议共用的受支持语言；语言选择不代表用户的居住地或服务资格。 */
public enum ContentLocale {
    ENGLISH("en-US", "English", false),
    SIMPLIFIED_CHINESE("zh-CN", "简体中文", false),
    TRADITIONAL_CHINESE("zh-TW", "繁體中文", false),
    SPANISH("es-ES", "Español", false),
    FRENCH("fr-FR", "Français", false),
    GERMAN("de-DE", "Deutsch", false),
    PORTUGUESE("pt-BR", "Português (Brasil)", false),
    RUSSIAN("ru-RU", "Русский", false),
    ARABIC("ar-SA", "العربية", true),
    JAPANESE("ja-JP", "日本語", false),
    KOREAN("ko-KR", "한국어", false),
    HINDI("hi-IN", "हिन्दी", false);

    private final String code;
    private final String label;
    private final boolean rightToLeft;

    ContentLocale(String code, String label, boolean rightToLeft) {
        this.code = code;
        this.label = label;
        this.rightToLeft = rightToLeft;
    }

    public String code() { return code; }
    public String label() { return label; }
    public boolean rightToLeft() { return rightToLeft; }
    public static List<ContentLocale> supported() { return List.of(values()); }

    public static ContentLocale require(String value) {
        String tag = value == null ? "en-US" : value.trim().replace('_', '-');
        if (tag.length() > 32 || !tag.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))
            throw new IllegalArgumentException("invalid content locale");
        String normalized = tag.toLowerCase(Locale.ROOT);
        String language = normalized.split("-", 2)[0];
        if (language.equals("zh")) {
            List<String> parts = Arrays.asList(normalized.split("-"));
            if (parts.contains("hans")) return SIMPLIFIED_CHINESE;
            if (parts.contains("hant")) return TRADITIONAL_CHINESE;
            return parts.contains("hant") || parts.contains("tw") || parts.contains("hk") || parts.contains("mo")
                    ? TRADITIONAL_CHINESE : SIMPLIFIED_CHINESE;
        }
        return switch (language) {
            case "en" -> ENGLISH;
            case "es" -> SPANISH;
            case "fr" -> FRENCH;
            case "de" -> GERMAN;
            case "pt" -> PORTUGUESE;
            case "ru" -> RUSSIAN;
            case "ar" -> ARABIC;
            case "ja" -> JAPANESE;
            case "ko" -> KOREAN;
            case "hi" -> HINDI;
            default -> throw new IllegalArgumentException("unsupported content locale");
        };
    }
}
