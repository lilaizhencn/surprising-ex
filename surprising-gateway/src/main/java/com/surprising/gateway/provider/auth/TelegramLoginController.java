package com.surprising.gateway.provider.auth;

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
    public ResponseEntity<TelegramLoginService.Started> start(@RequestHeader("X-Device-Id") String deviceId) {
        return noStore().body(login.start(deviceId));
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error) {
        boolean success = false;
        int status = 200;
        try {
            success = login.callback(state, code, error);
        } catch (IllegalArgumentException invalid) {
            status = 400;
        } catch (RuntimeException unavailable) {
            status = 503;
        }
        String title = success ? "授权完成" : "授权未完成";
        String message = success ? "请返回 Surprising 继续登录。" : "请返回 Surprising 重新发起登录。";
        // Browsers negotiate text/html. JSON exception handlers cannot serialize
        // under that content type and previously turned expired callbacks into 500s.
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .header("Referrer-Policy", "no-referrer").contentType(MediaType.TEXT_HTML)
                .body("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                        + "<title>Surprising</title><h1>" + title + "</h1><p>" + message + "</p></html>");
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

    public record Config(boolean enabled) {}
    public record Pending(boolean pending) {}
    public record Complete(
            @jakarta.validation.constraints.NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String flowId,
            @jakarta.validation.constraints.NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String verifier) {}
}
