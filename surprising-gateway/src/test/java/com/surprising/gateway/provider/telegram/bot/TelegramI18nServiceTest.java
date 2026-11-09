package com.surprising.gateway.provider.telegram.bot;

import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import com.surprising.gateway.provider.telegram.bot.service.TelegramBotSecurityService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramI18nService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramI18nServiceTest {

    private TelegramBotSecurityService securityService;
    private TelegramI18nService i18nService;

    @BeforeEach
    void setUp() {
        securityService = mock(TelegramBotSecurityService.class);
        i18nService = new TelegramI18nService(securityService);
    }

    @Test
    void resolvesLocaleFromTelegramUserLanguageCode() {
        TelegramUser zhUser = new TelegramUser(1001L, false, "张三", null, "zhangsan", "zh-hans");
        assertEquals(Locale.SIMPLIFIED_CHINESE, i18nService.resolveLocale(zhUser));

        TelegramUser ruUser = new TelegramUser(1002L, false, "Ivan", null, "ivan", "ru");
        assertEquals(Locale.forLanguageTag("ru"), i18nService.resolveLocale(ruUser));

        TelegramUser enUser = new TelegramUser(1003L, false, "John", null, "john", "en");
        assertEquals(Locale.ENGLISH, i18nService.resolveLocale(enUser));

        TelegramUser unknownUser = new TelegramUser(1004L, false, "Alex", null, "alex", "es");
        assertEquals(Locale.ENGLISH, i18nService.resolveLocale(unknownUser));
    }

    @Test
    void userPreferenceOverridesClientLanguageCode() {
        TelegramUser tgUser = new TelegramUser(2001L, false, "Bob", null, "bob", "zh-hans");
        assertEquals(Locale.SIMPLIFIED_CHINESE, i18nService.resolveLocale(tgUser));

        // 切换为英文
        i18nService.setUserLocale(tgUser.id(), 8888L, "en");
        assertEquals(Locale.ENGLISH, i18nService.resolveLocale(tgUser));
        verify(securityService).updatePreferredLanguage(eq(8888L), eq("en"));

        // 切换为俄文
        i18nService.setUserLocale(tgUser.id(), 8888L, "ru");
        assertEquals(Locale.forLanguageTag("ru"), i18nService.resolveLocale(tgUser));
        verify(securityService).updatePreferredLanguage(eq(8888L), eq("ru"));
    }

    @Test
    void translatesMessagesAcrossLocales() {
        // 中文
        String zhWelcome = i18nService.t("menu.welcome", Locale.SIMPLIFIED_CHINESE);
        assertTrue(zhWelcome.contains("欢迎使用 Surprising"));

        // 英文
        String enWelcome = i18nService.t("menu.welcome", Locale.ENGLISH);
        assertTrue(enWelcome.contains("Welcome to Surprising"));

        // 俄文
        String ruWelcome = i18nService.t("menu.welcome", Locale.forLanguageTag("ru"));
        assertTrue(ruWelcome.contains("Добро пожаловать"));
    }

    @Test
    void formatsDynamicArgumentsCorrectly() {
        TelegramUser tgUser = new TelegramUser(3001L, false, "Alice", null, "alice", "en");
        String text = i18nService.t("menu.account_uid", tgUser, 99999L);
        assertTrue(text.contains("99999"));
        assertTrue(text.contains("UID:"));
    }

    @Test
    void providesSupportedLanguagesList() {
        var langs = i18nService.getSupportedLanguages();
        assertNotNull(langs);
        assertEquals(3, langs.size());
        assertTrue(langs.stream().anyMatch(l -> "zh_CN".equals(l.code())));
        assertTrue(langs.stream().anyMatch(l -> "en".equals(l.code())));
        assertTrue(langs.stream().anyMatch(l -> "ru".equals(l.code())));
    }
}
