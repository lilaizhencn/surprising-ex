package com.surprising.gateway.provider.telegram.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramBotProperties {

    private String token = "";
    private String username = "surprising_ex_bot";
    private String webhookUrl = "";
    private String webhookSecret = "";
    private String webappUrl = "https://app.surprising.exchange";
    private boolean pollingEnabled = false;

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    public boolean isPollingEnabled() {
        return pollingEnabled;
    }

    public void setPollingEnabled(boolean pollingEnabled) {
        this.pollingEnabled = pollingEnabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getWebappUrl() {
        return webappUrl;
    }

    public void setWebappUrl(String webappUrl) {
        this.webappUrl = webappUrl;
    }
}
