package com.surprising.gateway.provider.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/security/api-keys")
public class UserApiKeyController {

    private final GatewayApiKeyService service;

    public UserApiKeyController(GatewayApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public List<GatewayApiKeyService.ApiKeyView> list(@RequestHeader("Authorization") String authorization) {
        return service.list(authorization);
    }

    @PostMapping
    public GatewayApiKeyService.CreatedApiKey create(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Security-Email-Code", required = false) String emailCode,
            @RequestHeader(value = "X-Security-TOTP-Code", required = false) String totpCode,
            @Valid @RequestBody CreateApiKeyRequest request,
            HttpServletRequest httpRequest) {
        try {
            return service.create(authorization, request.label(), request.permissions(), emailCode, totpCode,
                    httpRequest.getRemoteAddr());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @DeleteMapping
    public void revoke(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Security-Email-Code", required = false) String emailCode,
            @RequestHeader(value = "X-Security-TOTP-Code", required = false) String totpCode,
            @RequestBody RevokeApiKeyRequest request,
            HttpServletRequest httpRequest) {
        try {
            service.revoke(authorization, request.apiKey(), emailCode, totpCode, httpRequest.getRemoteAddr());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public record CreateApiKeyRequest(@NotBlank @Size(max = 80) String label, List<String> permissions) {
    }

    public record RevokeApiKeyRequest(@NotBlank String apiKey) {
    }
}
