package com.surprising.gateway.provider.telegram.bot.client;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties;
import com.surprising.gateway.provider.telegram.bot.model.keyboard.TelegramInlineKeyboardMarkup;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.Map;

@Component
public class TelegramBotClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);
    private static final String TG_API_BASE = "https://api.telegram.org/bot";

    private final TelegramBotProperties properties;
    private final RestClient restClient;

    public TelegramBotClient(TelegramBotProperties properties) {
        this(properties, RestClient.create());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramBotClient(TelegramBotProperties properties, GatewayProperties gatewayProperties,
                             HttpClient gatewayHttpClient) {
        this(properties, client(gatewayProperties, gatewayHttpClient));
    }

    TelegramBotClient(TelegramBotProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient client(GatewayProperties gatewayProperties, HttpClient gatewayHttpClient) {
        var factory = new JdkClientHttpRequestFactory(gatewayHttpClient);
        factory.setReadTimeout(gatewayProperties.getHttpClient().getReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    private ResponseEntity<String> post(String method, Object body, MediaType contentType) {
        return restClient.post()
                .uri(apiUrl(method))
                .contentType(contentType)
                .body(body)
                .retrieve()
                .toEntity(String.class);
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

            ResponseEntity<String> response = post("sendMessage", body, MediaType.APPLICATION_JSON);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.error("Failed to sendMessage to Telegram chatId={}: {}", chatId, ex.getMessage());
            return false;
        }
    }

    /** Sends one HTML notice. HTTP 429 stays claimed so the worker retries after the stale-claim window. */
    public SendOutcome sendHtml(long chatId, String html) {
        if (!isConfigured()) {
            return new SendOutcome(false, false, "telegram bot is not configured");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", html);
            body.put("parse_mode", "HTML");
            body.put("disable_web_page_preview", true);
            ResponseEntity<String> response = post("sendMessage", body, MediaType.APPLICATION_JSON);
            if (response.getStatusCode().is2xxSuccessful()) return new SendOutcome(true, false, null);
            return new SendOutcome(false, false, "telegram rejected the message");
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            boolean retry = ex.getStatusCode().value() == 429;
            String detail = ex.getResponseBodyAsString();
            return new SendOutcome(false, retry, clip(detail == null || detail.isBlank() ? ex.getMessage() : detail));
        } catch (RestClientException ex) {
            return new SendOutcome(false, false, clip(ex.getMessage()));
        }
    }

    private static String clip(String value) {
        if (value == null) return "telegram send failed";
        String single = value.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() <= 300 ? single : single.substring(0, 300);
    }

    public record SendOutcome(boolean accepted, boolean retry, String error) {}

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

            ResponseEntity<String> response = post("editMessageText", body, MediaType.APPLICATION_JSON);
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

            ResponseEntity<String> response = post("sendPhoto", body, MediaType.MULTIPART_FORM_DATA);
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

            ResponseEntity<String> response = post("answerCallbackQuery", body, MediaType.APPLICATION_JSON);
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

            ResponseEntity<String> response = post("setWebhook", body, MediaType.APPLICATION_JSON);
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

            ResponseEntity<String> response = post("setMyCommands", body, MediaType.APPLICATION_JSON);
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
            var response = restClient.get().uri(url).retrieve().body(GetUpdatesResponse.class);
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
