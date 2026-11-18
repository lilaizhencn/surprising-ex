package com.surprising.gateway.provider.telegram.bot;

import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.service.TelegramIdentityService;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.handler.TelegramAuthHandler;
import com.surprising.gateway.provider.telegram.bot.service.TelegramBotSecurityService;
import com.surprising.gateway.provider.telegram.bot.service.TelegramI18nService;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUser;
import org.junit.jupiter.api.Test;

class TelegramAuthHandlerTest {
    @Test void languagePreferenceIsNotReadFromPostgresOnEveryIdentityCheck() {
        var identity=mock(TelegramIdentityService.class);
        var settings=mock(TelegramBotSecurityService.class);
        var user=new TelegramUser(123L,false,"name",null,"name","en");
        when(identity.resolve("123","name")).thenReturn(7L);
        var handler=new TelegramAuthHandler(identity,mock(TelegramBotClient.class),new TelegramBotProperties(),mock(TelegramI18nService.class),settings);
        for(int i=0;i<100;i++) handler.resolveUserId(user);
        verify(settings,times(1)).getSettings(7);
        verify(identity,times(100)).resolve("123","name");
    }
}
