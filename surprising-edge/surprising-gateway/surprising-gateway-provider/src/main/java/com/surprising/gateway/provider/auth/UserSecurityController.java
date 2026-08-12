package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.UserMfaVerificationRequest;
import com.surprising.gateway.provider.auth.AuthModels.ChangePasswordRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminSessionRevokeResponse;
import com.surprising.gateway.provider.auth.AuthModels.LoginLogQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.SensitiveChallengeRequest;
import com.surprising.gateway.provider.auth.AuthModels.SensitiveChallengeVerificationRequest;
import com.surprising.gateway.provider.auth.AuthModels.UserSecuritySceneUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/security")
public class UserSecurityController {

    private final AuthService authService;
    private final UserSecurityService securityService;
    private final SensitiveActionVerificationService verificationService;
    private final AuthPersistenceService persistence;

    public UserSecurityController(AuthService authService,
                                  UserSecurityService securityService,
                                  SensitiveActionVerificationService verificationService,
                                  AuthPersistenceService persistence) {
        this.authService = authService;
        this.securityService = securityService;
        this.verificationService = verificationService;
        this.persistence = persistence;
    }

    @GetMapping("/mfa")
    public UserSecurityService.UserMfaStatus mfaStatus(@RequestHeader("Authorization") String authorization) {
        return securityService.status(principal(authorization).userId());
    }

    @PostMapping("/password")
    public void changePassword(@RequestHeader("Authorization") String authorization,
                               @Valid @RequestBody ChangePasswordRequest request) {
        try {
            long userId = principal(authorization).userId();
            securityService.requireCurrentPassword(userId, request.currentPassword());
            if (!verificationService.verify(userId, "CHANGE_PASSWORD", request.emailCode(),
                    request.totpCode(), java.time.Instant.now())) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                        "security verification is required or invalid");
            }
            securityService.updatePassword(userId, request.newPassword());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @PostMapping("/mfa/enroll")
    public UserSecurityService.UserMfaEnrollment enrollMfa(
            @RequestHeader("Authorization") String authorization) {
        try {
            return securityService.enrollMfa(principal(authorization).userId());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @PostMapping("/mfa/confirm")
    public UserSecurityService.UserMfaStatus confirmMfa(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody UserMfaVerificationRequest request) {
        try {
            return securityService.confirmMfa(principal(authorization).userId(), request.totpCode());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @PostMapping("/mfa/disable")
    public UserSecurityService.UserMfaStatus disableMfa(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody UserMfaVerificationRequest request) {
        try {
            return securityService.disableMfa(principal(authorization).userId(), request.totpCode());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @GetMapping("/scenes")
    public java.util.List<UserSecurityService.Scene> scenes(
            @RequestHeader("Authorization") String authorization) {
        return securityService.scenes(principal(authorization).userId());
    }

    @PutMapping("/scenes/{sceneCode}")
    public UserSecurityService.Scene updateScene(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String sceneCode,
            @Valid @RequestBody UserSecuritySceneUpdateRequest request) {
        try {
            long userId = principal(authorization).userId();
            if (!verificationService.verify(userId, "SECURITY_SETTINGS", request.emailCode(),
                    request.totpCode(), java.time.Instant.now())) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                        "security verification is required or invalid");
            }
            return securityService.updateScene(userId, sceneCode, request.enabled(), request.totpCode());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @GetMapping("/sessions")
    public AdminRefreshSessionQueryResponse sessions(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(value = "active", defaultValue = "true") Boolean active,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        try {
            long userId = principal(authorization).userId();
            return pageSessions(userId, active, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    public AdminSessionRevokeResponse revokeSession(
            @RequestHeader("Authorization") String authorization,
            @org.springframework.web.bind.annotation.PathVariable long sessionId) {
        try {
            long userId = principal(authorization).userId();
            boolean owned = persistence.refreshSessions(userId, null, 500).stream()
                    .anyMatch(session -> session.sessionId() == sessionId);
            if (!owned) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
            }
            java.time.Instant revokedAt = java.time.Instant.now();
            persistence.revokeRefreshSession(sessionId, revokedAt);
            return new AdminSessionRevokeResponse(1, revokedAt);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @PostMapping("/sessions/revoke-all")
    public AdminSessionRevokeResponse revokeAllSessions(
            @RequestHeader("Authorization") String authorization) {
        try {
            long userId = principal(authorization).userId();
            java.time.Instant revokedAt = java.time.Instant.now();
            int revoked = persistence.revokeUserRefreshSessions(userId, revokedAt);
            return new AdminSessionRevokeResponse(revoked, revokedAt);
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @GetMapping("/login-history")
    public LoginLogQueryResponse loginHistory(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(value = "result", required = false) String result,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        try {
            long userId = principal(authorization).userId();
            var page = persistence.loginLogPage(userId, result, limit, cursor, sort);
            return new LoginLogQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    private AdminRefreshSessionQueryResponse pageSessions(long userId,
                                                          Boolean active,
                                                          int limit,
                                                          String cursor,
                                                          String sort) {
        var page = persistence.refreshSessionsPage(userId, active, limit, cursor, sort);
        return new AdminRefreshSessionQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    @PostMapping("/verification/challenge")
    public SensitiveActionVerificationService.IssuedChallenge issueChallenge(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody SensitiveChallengeRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        try {
            return verificationService.issue(principal(authorization).userId(), request.sceneCode(),
                    httpRequest.getRemoteAddr(), java.time.Instant.now());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @PostMapping("/verification/verify")
    public boolean verifyChallenge(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody SensitiveChallengeVerificationRequest request) {
        try {
            return verificationService.verify(principal(authorization).userId(), request.sceneCode(),
                    request.emailCode(), request.totpCode(), java.time.Instant.now());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    private AuthModels.JwtPrincipal principal(String authorization) {
        try {
            return authService.authenticateBearer(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private ResponseStatusException badRequest(IllegalArgumentException ex) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
}
