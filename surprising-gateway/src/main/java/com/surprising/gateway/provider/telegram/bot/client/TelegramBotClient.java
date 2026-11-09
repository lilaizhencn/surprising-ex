package com.surprising.gateway.provider.telegram.bot.client;

import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class TelegramBotClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);
    private static final String TG_API_BASE = "https://api.telegram.org/bot";

    private final TelegramBotProperties properties;
    private final RestTemplate restTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramBotClient(TelegramBotProperties properties) {
        this(properties, new RestTemplate());
    }

    public TelegramBotClient(TelegramBotProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    private String apiUrl(String method) {
        return TG_API_BASE + properties.getToken() + "/" + method;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public boolean sendMessage(long chatId, String text, Object replyMarkup) {
        if (!isConfigured()) {
            log.debug("Telegram Bot token not configured, skipping sendMessage to chatId={}", chatId);
            return false;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "HTML");
            body.put("disable_web_page_preview", true);
            if (replyMarkup != null) {
                body.put("reply_markup", replyMarkup);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl("sendMessage"), entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.error("Failed to sendMessage to Telegram chatId={}: {}", chatId, ex.getMessage());
            return false;
        }
    }

    public boolean editMessageText(long chatId, long messageId, String text, TelegramInlineKeyboardMarkup replyMarkup) {
        if (!isConfigured()) {
            return false;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("message_id", messageId);
            body.put("text", text);
            body.put("parse_mode", "HTML");
            body.put("disable_web_page_preview", true);
            if (replyMarkup != null) {
                body.put("reply_markup", replyMarkup);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl("editMessageText"), entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.error("Failed to editMessageText chatId={}, messageId={}: {}", chatId, messageId, ex.getMessage());
            return false;
        }
    }

    public boolean sendPhoto(long chatId, byte[] photoBytes, String filename, String caption, Object replyMarkup) {
        if (!isConfigured()) {
            return false;
        }
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("chat_id", chatId);
            if (caption != null && !caption.isBlank()) {
                body.add("caption", caption);
                body.add("parse_mode", "HTML");
            }
            if (replyMarkup != null) {
                body.add("reply_markup", replyMarkup);
            }

            ByteArrayResource resource = new ByteArrayResource(photoBytes) {
                @Override
                public String getFilename() {
                    return filename != null ? filename : "qrcode.png";
                }
            };
            body.add("photo", resource);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl("sendPhoto"), entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.error("Failed to sendPhoto to chatId={}: {}", chatId, ex.getMessage());
            return false;
        }
    }

    public boolean answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        if (!isConfigured() || callbackQueryId == null || callbackQueryId.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("callback_query_id", callbackQueryId);
            if (text != null && !text.isBlank()) {
                body.put("text", text);
            }
            body.put("show_alert", showAlert);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl("answerCallbackQuery"), entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.warn("Failed to answerCallbackQuery id={}: {}", callbackQueryId, ex.getMessage());
            return false;
        }
    }

    public boolean setWebhook(String webhookUrl, String secretToken) {
        if (!isConfigured() || webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("url", webhookUrl);
            if (secretToken != null && !secretToken.isBlank()) {
                body.put("secret_token", secretToken);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl("setWebhook"), entity, String.class);
            log.info("Telegram setWebhook response: {}", response.getBody());
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.error("Failed to setWebhook to {}: {}", webhookUrl, ex.getMessage());
            return false;
        }
    }

    public record BotCommand(String command, String description) {}

    public boolean setMyCommands(java.util.List<BotCommand> commands, String languageCode) {
        if (!isConfigured() || commands == null || commands.isEmpty()) {
            return false;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("commands", commands);
            if (languageCode != null && !languageCode.isBlank()) {
                body.put("language_code", languageCode.trim().toLowerCase());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl("setMyCommands"), entity, String.class);
            log.info("Telegram setMyCommands (lang={}) response: {}", languageCode, response.getBody());
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.warn("Failed to setMyCommands for lang={}: {}", languageCode, ex.getMessage());
            return false;
        }
    }

    public java.util.List<com.surprising.gateway.provider.telegram.bot.model.TelegramUpdate> getUpdates(long offset, int limit, int timeoutSec) {
        if (!isConfigured()) {
            return java.util.List.of();
        }
        try {
            String url = apiUrl("getUpdates") + "?offset=" + offset + "&limit=" + limit + "&timeout=" + timeoutSec;
            var response = restTemplate.getForObject(url, GetUpdatesResponse.class);
            if (response != null && response.result() != null) {
                return response.result();
            }
        } catch (Exception ex) {
            log.debug("Telegram getUpdates exception: {}", ex.getMessage());
        }
        return java.util.List.of();
    }

    public record GetUpdatesResponse(
            boolean ok,
            java.util.List<com.surprising.gateway.provider.telegram.bot.model.TelegramUpdate> result
    ) {}
}
