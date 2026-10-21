package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.LoginVerificationModels;

import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 登录因素的绑定入口；必须验证已有全部因素及新联系方式，成功后才启用。 */
@RestController
@RequestMapping("/api/v1/security/login-verification")
public class LoginVerificationSettingsController {
    private final AuthService auth;
    private final LoginVerificationService verification;

    public LoginVerificationSettingsController(
            AuthService auth, LoginVerificationService verification) {
        this.auth = auth;
        this.verification = verification;
    }

    @GetMapping
    public List<LoginVerificationModels.Setting> methods(
            @RequestHeader("Authorization") String bearer) {
        return verification.settings(auth.authenticateBearer(bearer).userId());
    }

    @GetMapping("/authentication")
    public ResponseEntity<LoginVerificationModels.PrimaryAuthentication> authentication(
            @RequestHeader("Authorization") String bearer) {
        var principal = auth.authenticateBearer(bearer);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(verification.primaryAuthentication(principal.userId(), Instant.now(),
                        auth.primaryAuthenticatedAt(principal)));
    }

    @PostMapping("/{method}/verify-password")
    public void verifyPassword(
            @RequestHeader("Authorization") String bearer,
            @PathVariable String method,
            @Valid @RequestBody PasswordVerificationRequest request) {
        var principal = auth.authenticateBearer(bearer);
        verification.verifySettingPassword(principal.userId(), method, request.currentPassword(),
                Instant.now(), auth.primaryAuthenticatedAt(principal));
    }

    public record PasswordVerificationRequest(
            @jakarta.validation.constraints.Size(max = 128) String currentPassword) {}

    @PostMapping("/{method}/bind")
    public LoginVerificationModels.BindingResponse begin(
            @RequestHeader("Authorization") String bearer,
            @PathVariable String method,
            @Valid @RequestBody LoginVerificationModels.SettingRequest request) {
        var principal = auth.authenticateBearer(bearer);
        return verification.beginBinding(principal.userId(), method, request, Instant.now(),
                auth.primaryAuthenticatedAt(principal));
    }

    @PostMapping("/{method}/resend")
    public LoginVerificationModels.ChallengeResponse resend(
            @RequestHeader("Authorization") String bearer,
            @PathVariable String method,
            @Valid @RequestBody LoginVerificationModels.ResendRequest request) {
        return verification.resendBindingCode(
                auth.authenticateBearer(bearer).userId(), method, request, Instant.now());
    }

    @PostMapping("/{method}/confirm")
    public void confirm(
            @RequestHeader("Authorization") String bearer,
            @PathVariable String method,
            @Valid @RequestBody LoginVerificationModels.SettingConfirmation request) {
        var principal = auth.authenticateBearer(bearer);
        verification.confirmBinding(principal.userId(), method, request, Instant.now(),
                auth.primaryAuthenticatedAt(principal));
    }

    @ExceptionHandler(LoginVerificationService.VerificationFailure.class)
    public ResponseEntity<Map<String, String>> failure(
            LoginVerificationService.VerificationFailure e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
