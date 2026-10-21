package com.surprising.gateway.provider.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** QR codes identify a public profile; scanning never authorizes a payment or account action. */
@RestController
@RequestMapping("/api/v1/users")
public class UserCardController {
    private final AuthService auth;
    private final AuthPersistenceService users;

    public UserCardController(AuthService auth, AuthPersistenceService users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/scan")
    public PublicProfile scan(@RequestHeader("Authorization") String authorization,
                              @Valid @RequestBody ScanRequest request) {
        try { auth.authenticateBearer(authorization); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in required"); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access denied"); }
        long userId = parseUserId(request.qrCode());
        var user = users.user(userId).filter(value -> !"FROZEN".equals(value.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user is unavailable"));
        String name = user.displayName();
        if (name == null || name.isBlank()) name = user.username();
        if (name == null || name.isBlank()) name = "UID " + userId;
        return new PublicProfile(userId, name);
    }

    static long parseUserId(String code) {
        if (code == null || !code.matches("surprising://user/[1-9][0-9]{0,18}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid user QR code");
        try { return Long.parseLong(code.substring("surprising://user/".length())); }
        catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid user QR code");
        }
    }

    public record ScanRequest(@NotBlank @Size(max = 128) String qrCode) {}
    public record PublicProfile(long userId, String displayName) {}
}
