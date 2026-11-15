package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.service.GoogleLoginService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.config.GoogleLoginProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/google")
public class GoogleLoginController {
    private final GoogleLoginService login;
    private final GoogleLoginProperties properties;
    private final AuthClientMetadataBinding clients;
    public GoogleLoginController(GoogleLoginService login, GoogleLoginProperties properties, AuthClientMetadataBinding clients) {
        this.login = login;
        this.properties = properties;
        this.clients = clients;
    }

    @GetMapping("/config")
    public ResponseEntity<Config> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Config(properties.enabled(),
                properties.enabled() ? properties.getWebClientId() : "", properties.iosEnabled() ? properties.getIosClientId() : "",
                properties.androidEnabled(), properties.iosEnabled()));
    }
    @PostMapping("/challenge")
    public ResponseEntity<?> challenge(HttpServletRequest request) {
        if (!properties.enabled()) throw new IllegalStateException("Google login is not configured");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(login.challenge(clients.bind(request).deviceId()));
    }
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody Login request, HttpServletRequest http) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                    login.login(request.credential(), request.nonce(), request.platform(), request.inviteCode(), clients.bind(http)));
        } catch (IllegalStateException failure) {
            throw new com.surprising.gateway.provider.controller.advice.GlobalGatewayExceptionHandler.StatusException(
                    properties.enabled() ? 403 : 503,
                    properties.enabled() ? "Google login is not permitted" : "Google login is not configured");
        }
    }
    public record Config(boolean enabled, String webClientId, String iosClientId, boolean androidEnabled, boolean iosEnabled) {}
    public record Login(@NotBlank @Size(max = 16384) String credential,
                        @Pattern(regexp = "[A-Za-z0-9_-]{43}") String nonce,
                        @NotNull @Pattern(regexp = "WEB|ANDROID|IOS") String platform,
                        @Size(max = 64) String inviteCode) {}
}
