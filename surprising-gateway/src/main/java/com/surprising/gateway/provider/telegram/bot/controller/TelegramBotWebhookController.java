package com.surprising.gateway.provider.telegram.bot.controller;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.TelegramUpdate;
import com.surprising.gateway.provider.telegram.bot.service.TelegramUpdateDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/telegram/bot")
public class TelegramBotWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotWebhookController.class);

    private final TelegramBotProperties properties;
    private final TelegramBotClient botClient;
    private final TelegramUpdateDispatcher dispatcher;

    public TelegramBotWebhookController(TelegramBotProperties properties,
                                        TelegramBotClient botClient,
                                        TelegramUpdateDispatcher dispatcher) {
        this.properties = properties;
        this.botClient = botClient;
        this.dispatcher = dispatcher;
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> onWebhookUpdate(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken,
            @RequestBody TelegramUpdate update) {

        // 验签：如果配置了 Webhook Secret，必须匹配
        if (properties.getWebhookSecret() != null && !properties.getWebhookSecret().isBlank()) {
            if (!properties.getWebhookSecret().equals(secretToken)) {
                log.warn("Unauthorized Telegram webhook update attempt. Secret token mismatch!");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden");
            }
        }

        try {
            dispatcher.dispatch(update);
        } catch (Exception ex) {
            log.error("Unhandled error processing Telegram webhook update: {}", ex.getMessage(), ex);
        }

        return ResponseEntity.ok("OK");
    }

    @PostMapping("/setup-webhook")
    public ResponseEntity<?> setupWebhook(@RequestParam(required = false) String webhookUrl) {
        String url = (webhookUrl != null && !webhookUrl.isBlank()) ? webhookUrl : properties.getWebhookUrl();
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "webhookUrl is empty and not configured in properties"));
        }
        boolean ok = botClient.setWebhook(url, properties.getWebhookSecret());
        return ResponseEntity.ok(Map.of("success", ok, "webhookUrl", url));
    }
}
