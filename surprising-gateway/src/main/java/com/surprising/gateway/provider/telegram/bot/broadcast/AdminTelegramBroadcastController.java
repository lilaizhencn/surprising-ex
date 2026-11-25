package com.surprising.gateway.provider.telegram.bot.broadcast;

import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.telegram.bot.broadcast.TelegramBroadcastService.BroadcastView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/telegram/broadcasts")
public class AdminTelegramBroadcastController {

    private final AuthService auth;
    private final TelegramBroadcastService broadcasts;

    public AdminTelegramBroadcastController(AuthService auth, TelegramBroadcastService broadcasts) {
        this.auth = auth;
        this.broadcasts = broadcasts;
    }

    @GetMapping
    public BroadcastPage list(@RequestHeader("Authorization") String authorization) {
        authorize(authorization);
        return new BroadcastPage(broadcasts.list());
    }

    @PostMapping
    public BroadcastView create(@RequestHeader("Authorization") String authorization,
                                @Valid @RequestBody CreateRequest request) {
        var principal = authorize(authorization);
        try {
            return broadcasts.create(principal.userId(), request.html());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private com.surprising.gateway.provider.auth.model.JwtPrincipal authorize(String authorization) {
        try {
            return auth.requireAdminPermission(authorization, "admin.telegram.broadcast");
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    public record CreateRequest(@NotBlank @Size(max = 60000) String html) {}

    public record BroadcastPage(List<BroadcastView> broadcasts) {}
}
