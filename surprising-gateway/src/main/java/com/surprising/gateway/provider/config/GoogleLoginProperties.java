package com.surprising.gateway.provider.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "google.login")
public class GoogleLoginProperties {
    private String webClientId = "";
    private String iosClientId = "";
    private List<String> androidClientIds = List.of();

    public boolean enabled() { return clientId(webClientId); }
    public boolean iosEnabled() { return enabled() && clientId(iosClientId); }
    public boolean androidEnabled() {
        return enabled() && androidClientIds != null && !androidClientIds.isEmpty() && androidClientIds.stream().allMatch(GoogleLoginProperties::clientId);
    }
    private static boolean clientId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com");
    }
}
