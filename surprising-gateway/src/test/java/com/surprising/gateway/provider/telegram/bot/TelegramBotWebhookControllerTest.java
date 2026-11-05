package com.surprising.gateway.provider.telegram.bot;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.controller.TelegramBotWebhookController;
import com.surprising.gateway.provider.telegram.bot.model.*;
import com.surprising.gateway.provider.telegram.bot.service.TelegramUpdateDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TelegramBotWebhookControllerTest {

    private TelegramBotProperties properties;
    private TelegramBotClient botClient;
    private TelegramUpdateDispatcher dispatcher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new TelegramBotProperties();
        properties.setWebhookSecret("my-secret-token");
        botClient = mock(TelegramBotClient.class);
        dispatcher = mock(TelegramUpdateDispatcher.class);
        TelegramBotWebhookController controller = new TelegramBotWebhookController(properties, botClient, dispatcher);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rejectsWebhookWithInvalidSecret() throws Exception {
        String json = "{\"update_id\": 1}";

        mockMvc.perform(post("/api/v1/telegram/bot/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Telegram-Bot-Api-Secret-Token", "wrong-secret")
                        .content(json))
                .andExpect(status().isForbidden());

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void acceptsWebhookWithValidSecretAndDispatches() throws Exception {
        String json = """
                {
                    "update_id": 1,
                    "message": {
                        "message_id": 10,
                        "date": 12345678,
                        "text": "/start",
                        "from": {
                            "id": 123456,
                            "first_name": "Test"
                        },
                        "chat": {
                            "id": 987654,
                            "type": "private"
                        }
                    }
                }
                """;

        mockMvc.perform(post("/api/v1/telegram/bot/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Telegram-Bot-Api-Secret-Token", "my-secret-token")
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));

        verify(dispatcher).dispatch(any());
    }
}

