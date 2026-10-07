package com.surprising.gateway.provider.auth;

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
    public List<LoginVerificationService.Setting> methods(
            @RequestHeader("Authorization") String bearer) {
        return verification.settings(auth.authenticateBearer(bearer).userId());
    }

    @PostMapping("/{method}/verify-password")
    public void verifyPassword(
            @RequestHeader("Authorization") String bearer,
            @PathVariable String method,
            @Valid @RequestBody PasswordVerificationRequest request) {
        verification.verifySettingPassword(
                auth.authenticateBearer(bearer).userId(), method, request.currentPassword());
    }

    public record PasswordVerificationRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 128) String currentPassword) {}

    @PostMapping("/{method}/bind")
    public LoginVerificationService.BindingResponse begin(
            @RequestHeader("Authorization") String bearer,
            @PathVariable String method,
            @Valid @RequestBody LoginVerificationService.SettingRequest request) {
        return verification.beginBinding(
                auth.authenticateBearer(bearer).userId(), method, request, Instant.now());
    }

    @PostMapping("/{method}/confirm")
    public void confirm(
            @RequestHeader("Authorization") String bearer,
            @PathVariable String method,
            @Valid @RequestBody LoginVerificationService.SettingConfirmation request) {
        verification.confirmBinding(
                auth.authenticateBearer(bearer).userId(), method, request, Instant.now());
    }

    @ExceptionHandler(LoginVerificationService.VerificationFailure.class)
    public ResponseEntity<Map<String, String>> failure(
            LoginVerificationService.VerificationFailure e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
