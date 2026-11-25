package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.LoginVerificationModels;

import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.model.CurrentUser;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

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
    public List<LoginVerificationModels.Setting> methods(@CurrentUser long userId) {
        return verification.settings(userId);
    }

    @GetMapping("/authentication")
    public ResponseEntity<LoginVerificationModels.PrimaryAuthentication> authentication(
            @CurrentUser JwtPrincipal principal) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(verification.primaryAuthentication(principal.userId(), Instant.now(),
                        auth.primaryAuthenticatedAt(principal)));
    }

    @PostMapping("/{method}/verify-password")
    public void verifyPassword(
            @CurrentUser JwtPrincipal principal,
            @PathVariable String method,
            @Valid @RequestBody PasswordVerificationRequest request) {
        verification.verifySettingPassword(principal.userId(), method, request.currentPassword(),
                Instant.now(), auth.primaryAuthenticatedAt(principal));
    }

    public record PasswordVerificationRequest(
            @jakarta.validation.constraints.Size(max = 128) String currentPassword) {}

    @PostMapping("/{method}/bind")
    public LoginVerificationModels.BindingResponse begin(
            @CurrentUser JwtPrincipal principal,
            @PathVariable String method,
            @Valid @RequestBody LoginVerificationModels.SettingRequest request) {
        return verification.beginBinding(principal.userId(), method, request, Instant.now(),
                auth.primaryAuthenticatedAt(principal));
    }

    @PostMapping("/{method}/resend")
    public LoginVerificationModels.ChallengeResponse resend(
            @CurrentUser long userId,
            @PathVariable String method,
            @Valid @RequestBody LoginVerificationModels.ResendRequest request) {
        return verification.resendBindingCode(
                userId, method, request, Instant.now());
    }

    @PostMapping("/{method}/confirm")
    public void confirm(
            @CurrentUser JwtPrincipal principal,
            @PathVariable String method,
            @Valid @RequestBody LoginVerificationModels.SettingConfirmation request) {
        verification.confirmBinding(principal.userId(), method, request, Instant.now(),
                auth.primaryAuthenticatedAt(principal));
    }
}
