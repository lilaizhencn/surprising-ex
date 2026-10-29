package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.auth.model.ApiKeyModels.*;
import com.surprising.gateway.provider.config.GatewayProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/security/api-keys")
public class UserApiKeyController {

    private final GatewayApiKeyService service;
    private final ApiKeyRequestBinding binding;

    public UserApiKeyController(GatewayApiKeyService service, GatewayProperties properties) {
        this.service = service;
        this.binding = new ApiKeyRequestBinding(properties);
    }

    @GetMapping
    public List<ApiKeyView> list(@RequestHeader("Authorization") String authorization) {
        return service.list(authorization);
    }

    @PostMapping
    public CreatedApiKey create(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Security-Email-Code", required = false) String emailCode,
            @RequestHeader(value = "X-Security-TOTP-Code", required = false) String totpCode,
            @Valid @RequestBody CreateApiKeyRequest request,
            HttpServletRequest httpRequest) {
        try {
            return service.create(authorization, binding.label(request.label()), binding.normalizePermissions(request.permissions()), binding.normalizeIpAllowlist(request.ipAllowlist()), emailCode, totpCode,
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
            service.revoke(authorization, binding.requireApiKey(request.apiKey()), emailCode, totpCode, httpRequest.getRemoteAddr());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PatchMapping
    public void update(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Security-Email-Code", required = false) String emailCode,
            @RequestHeader(value = "X-Security-TOTP-Code", required = false) String totpCode,
            @Valid @RequestBody UpdateApiKeyRequest request,
            HttpServletRequest httpRequest) {
        try {
            service.update(authorization, binding.requireApiKey(request.apiKey()), binding.label(request.label()),
                    binding.normalizePermissions(request.permissions()), binding.normalizeIpAllowlist(request.ipAllowlist()), emailCode, totpCode,
                    httpRequest.getRemoteAddr());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

}
