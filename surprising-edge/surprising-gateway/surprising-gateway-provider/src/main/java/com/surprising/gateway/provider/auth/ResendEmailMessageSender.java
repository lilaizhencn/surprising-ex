package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

@Component
public class ResendEmailMessageSender implements EmailMessageSender {

    private final GatewayProperties properties;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ResendEmailMessageSender(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(String recipient, String subject, String text) {
        GatewayProperties.Security security = properties.getSecurity();
        if (security.getResendApiKey().isBlank() || security.getResendFrom().isBlank()) {
            throw new IllegalStateException("resend email provider is not configured");
        }
        String payload = "{\"from\":\"" + escape(security.getResendFrom())
                + "\",\"to\":[\"" + escape(recipient)
                + "\"],\"subject\":\"" + escape(subject)
                + "\",\"text\":\"" + escape(text) + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(security.getResendBaseUrl() + "/emails"))
                .header("Authorization", "Bearer " + security.getResendApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("resend email delivery failed: HTTP " + response.statusCode());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("resend email delivery interrupted", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("resend email delivery failed", ex);
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
