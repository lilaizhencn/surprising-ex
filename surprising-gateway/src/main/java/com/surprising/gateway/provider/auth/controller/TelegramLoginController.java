package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.service.TelegramLoginService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/telegram")
public class TelegramLoginController {
    private final TelegramLoginService login;

    public TelegramLoginController(TelegramLoginService login) { this.login = login; }

    @GetMapping("/config")
    public ResponseEntity<Config> config() { return noStore().body(new Config(login.enabled())); }

    @PostMapping("/start")
    public ResponseEntity<TelegramLoginService.Started> start(@RequestHeader("X-Device-Id") String deviceId,
            @RequestBody(required = false) Start request) {
        return noStore().body(login.start(deviceId, request != null && Boolean.TRUE.equals(request.returnToApp())));
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error) {
        TelegramLoginService.Callback result = null;
        int status = 200;
        try {
            result = login.callback(state, code, error);
        } catch (IllegalArgumentException invalid) {
            status = 400;
        } catch (RuntimeException unavailable) {
            status = 503;
        }
        boolean success = result != null && result.success();
        String title = success ? "授权完成" : "授权未完成";
        String message = success ? "请返回 Surprising 继续登录。" : "请返回 Surprising 重新发起登录。";
        String nonce = java.util.UUID.randomUUID().toString();
        // Only a claimed mobile flow supplies this fixed, token-free return URL.
        String returnLink = result == null || result.returnUrl() == null ? ""
                : "<a id=\"return\" href=\"" + result.returnUrl() + "\">返回 Surprising</a>"
                  + "<p>未自动打开时，请点击上方按钮。</p><script nonce=\"" + nonce
                  + "\">window.location.replace(document.getElementById('return').href);</script>";
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
                        + "script-src 'nonce-" + nonce + "'; style-src 'nonce-" + nonce + "'")
                .header("Referrer-Policy", "no-referrer").contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .body("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                        + "<title>Surprising</title><style nonce=\"" + nonce + "\">"
                        + "body{font:16px system-ui;background:#111318;color:#f2f0f3;text-align:center;padding:64px 20px}"
                        + "a{display:inline-block;background:#7c9aff;color:#111318;padding:14px 24px;"
                        + "border-radius:12px;text-decoration:none}p{line-height:1.6}</style>"
                        + "<h1>" + title + "</h1><p>" + message + "</p>" + returnLink + "</html>");
    }

    @PostMapping("/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody Complete request,
            @RequestHeader("X-Device-Id") String deviceId, HttpServletRequest http) {
        var result = login.complete(request.flowId(), request.verifier(), deviceId, http);
        return result == null ? noStore().body(new Pending(true)) : noStore().body(result);
    }

    @ExceptionHandler(LoginVerificationService.VerificationFailure.class)
    public ResponseEntity<?> verification(LoginVerificationService.VerificationFailure error) {
        int status = error.getMessage().contains("RATE_LIMITED") ? 429
                : error.getMessage().contains("UNAVAILABLE") || error.getMessage().contains("DELIVERY_FAILED") ? 503 : 400;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> unavailable(IllegalStateException error) {
        return ResponseEntity.status(login.enabled() ? 403 : 503).cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("message", login.enabled() ? "Telegram login is not permitted" : "Telegram login is not configured"));
    }

    private static ResponseEntity.BodyBuilder noStore() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore());
    }

    public record Start(Boolean returnToApp) {}
    public record Config(boolean enabled) {}
    public record Pending(boolean pending) {}
    public record Complete(
            @jakarta.validation.constraints.NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String flowId,
            @jakarta.validation.constraints.NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String verifier) {}
}
