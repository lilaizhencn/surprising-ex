package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.LoginVerificationModels;

import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.auth.service.UserSecurityService;
import com.surprising.gateway.provider.auth.utils.ClientIpResolver;

import com.surprising.gateway.provider.auth.model.AuthModels.UserMfaVerificationRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.ChangePasswordRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminRefreshSessionQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminSessionRevokeResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.LoginLogQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.RevokeOtherSessionsRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.SensitiveChallengeRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.SensitiveChallengeVerificationRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.UserSecuritySceneUpdateRequest;
import jakarta.validation.Valid;
import com.surprising.gateway.provider.exception.SecurityVerificationRequiredException;
import com.surprising.gateway.provider.model.UserDeviceStatus;
import com.surprising.gateway.provider.model.UserIpStatus;
import com.surprising.gateway.provider.service.UserAccessService;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/security")
public class UserSecurityController {

    private final AuthService authService;
    private final UserSecurityService securityService;
    private final LoginVerificationService loginVerification;
    private final SensitiveActionVerificationService verificationService;
    private final AuthPersistenceService persistence;
    private final UserAccessService access;
    private final ClientIpResolver clientIpResolver;

    public UserSecurityController(AuthService authService,
                                  UserSecurityService securityService,
                                  LoginVerificationService loginVerification,
                                  SensitiveActionVerificationService verificationService,
                                  AuthPersistenceService persistence,
                                  UserAccessService access,
                                  com.surprising.gateway.provider.config.GatewayProperties properties) {
        this.authService = authService;
        this.securityService = securityService;
        this.loginVerification = loginVerification;
        this.verificationService = verificationService;
        this.persistence = persistence;
        this.access = access;
        this.clientIpResolver = new ClientIpResolver(properties);
    }

    @GetMapping("/mfa")
    public UserSecurityService.UserMfaStatus mfaStatus(@RequestHeader("Authorization") String authorization) {
        return securityService.status(principal(authorization).userId());
    }

    @PostMapping("/mfa/enroll")
    public LoginVerificationModels.BindingResponse beginMfaEnrollment(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody MfaChangeStartRequest request) {
        var principal = principal(authorization);
        return loginVerification.beginBinding(principal.userId(), "TOTP",
                new LoginVerificationModels.SettingRequest(request.currentPassword(), null, true), Instant.now(),
                authService.primaryAuthenticatedAt(principal));
    }

    @PostMapping("/mfa/confirm")
    public UserSecurityService.UserMfaStatus confirmMfaEnrollment(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody MfaChangeConfirmation request) {
        var principal = principal(authorization);
        long userId = principal.userId();
        loginVerification.confirmBinding(userId, "TOTP", new LoginVerificationModels.SettingConfirmation(
                request.currentPassword(), request.codes()), Instant.now(), authService.primaryAuthenticatedAt(principal));
        return securityService.status(userId);
    }

    @PostMapping("/mfa/disable")
    public LoginVerificationModels.BindingResponse beginMfaDisable(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody MfaChangeStartRequest request) {
        var principal = principal(authorization);
        return loginVerification.beginBinding(principal.userId(), "TOTP",
                new LoginVerificationModels.SettingRequest(request.currentPassword(), null, false), Instant.now(),
                authService.primaryAuthenticatedAt(principal));
    }

    @PostMapping("/mfa/disable/confirm")
    public UserSecurityService.UserMfaStatus confirmMfaDisable(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody MfaChangeConfirmation request) {
        var principal = principal(authorization);
        long userId = principal.userId();
        loginVerification.confirmBinding(userId, "TOTP", new LoginVerificationModels.SettingConfirmation(
                request.currentPassword(), request.codes()), Instant.now(), authService.primaryAuthenticatedAt(principal));
        return securityService.status(userId);
    }

    @PostMapping("/password")
    public void changePassword(@RequestHeader("Authorization") String authorization,
                               @Valid @RequestBody ChangePasswordRequest request) {
        long userId = principal(authorization).userId();
        mapAccessFailure(() -> access.changePassword(userId, request.currentPassword(), request.newPassword(),
                request.emailCode(), request.totpCode()));
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
            return access.updateScene(userId, sceneCode, request.enabled(), request.emailCode(), request.totpCode());
        } catch (SecurityVerificationRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
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

    @GetMapping("/devices")
    public List<UserDeviceStatus> devices(@RequestHeader("Authorization") String authorization,
                                          @RequestParam(defaultValue="100") int limit,
                                          @RequestParam(defaultValue="0") int offset) {
        requireAccessPage(limit, offset);
        return access.devices(principal(authorization), limit, offset);
    }

    @PostMapping("/devices/{deviceId}/revoke")
    public AuthModels.AdminSessionRevokeResponse revokeDevice(
            @RequestHeader("Authorization") String authorization, @PathVariable String deviceId) {
        long userId = principal(authorization).userId();
        return access.revokeDevice(userId, normalizedDeviceId(deviceId));
    }

    @PostMapping("/devices/{deviceId}/block")
    public void blockDevice(@RequestHeader("Authorization") String authorization,
                            @PathVariable String deviceId, @RequestBody AccessChangeRequest request) {
        var principal = principal(authorization);
        String normalized = normalizedDeviceId(deviceId);
        mapAccessFailure(() -> access.blockDevice(principal, normalized, request.emailCode(), request.totpCode()));
    }

    @PostMapping("/devices/{deviceId}/unblock")
    public void unblockDevice(@RequestHeader("Authorization") String authorization,
                              @PathVariable String deviceId, @RequestBody AccessChangeRequest request) {
        long userId = principal(authorization).userId();
        String normalized = normalizedDeviceId(deviceId);
        mapAccessFailure(() -> access.unblockDevice(userId, normalized, request.emailCode(), request.totpCode()));
    }

    @GetMapping("/ips")
    public List<UserIpStatus> ips(@RequestHeader("Authorization") String authorization,
                                  @RequestParam(defaultValue="100") int limit,
                                  @RequestParam(defaultValue="0") int offset) {
        requireAccessPage(limit, offset);
        return access.ips(principal(authorization).userId(), limit, offset);
    }

    @PostMapping("/ips/block")
    public void blockIp(@RequestHeader("Authorization") String authorization,
                        @RequestBody IpAccessChangeRequest request,
                        jakarta.servlet.http.HttpServletRequest httpRequest) {
        long userId = principal(authorization).userId();
        String ip = normalizedIp(request.ipAddress());
        String currentIp = clientIpResolver.resolve(httpRequest);
        mapAccessFailure(() -> access.blockIp(userId, ip, currentIp, request.emailCode(), request.totpCode()));
    }

    @PostMapping("/ips/unblock")
    public void unblockIp(@RequestHeader("Authorization") String authorization,
                          @RequestBody IpAccessChangeRequest request) {
        long userId = principal(authorization).userId();
        String ip = normalizedIp(request.ipAddress());
        mapAccessFailure(() -> access.unblockIp(userId, ip, request.emailCode(), request.totpCode()));
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    public AdminSessionRevokeResponse revokeSession(
            @RequestHeader("Authorization") String authorization,
            @org.springframework.web.bind.annotation.PathVariable long sessionId) {
        try {
            long userId = principal(authorization).userId();
            java.time.Instant revokedAt = java.time.Instant.now();
            int revoked = persistence.revokeRefreshSessionForUser(userId, sessionId, revokedAt);
            if (revoked == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
            }
            return new AdminSessionRevokeResponse(revoked, revokedAt);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
    }

    @PostMapping("/sessions/revoke-all")
    public AdminSessionRevokeResponse revokeAllSessions(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody RevokeOtherSessionsRequest request) {
        try {
            return authService.revokeOtherRefreshSessions(authorization, request.refreshToken());
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
            var page = persistence.loginLogPage(userId, AuditLogRequestBinding.result(result),
                    AuditLogRequestBinding.page(limit, cursor, sort));
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
        AuthPageRequestBinding.sessionUser(userId);
        var page = persistence.refreshSessionsPage(userId, active, AuthPageRequestBinding.created(limit, cursor, sort));
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

    private void mapAccessFailure(Runnable action) {
        try { action.run(); }
        catch (SecurityVerificationRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) { throw badRequest(ex); }
    }

    private void requireAccessPage(int limit, int offset) {
        if (limit < 1 || limit > 500 || offset < 0 || offset > 10000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1-500 and offset 0-10000");
    }

    private String normalizedDeviceId(String deviceId) {
        try {
            String canonical = UUID.fromString(deviceId).toString();
            if (!canonical.equalsIgnoreCase(deviceId)) throw new IllegalArgumentException();
            return canonical;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid device ID", ex);
        }
    }

    private String normalizedIp(String ipAddress) {
        String value = ipAddress == null ? "" : ipAddress.trim();
        if (value.contains("/") || !clientIpResolver.isValidRule(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid IP address");
        }
        return value;
    }

    public record AccessChangeRequest(String emailCode, String totpCode) {}
    public record IpAccessChangeRequest(String ipAddress, String emailCode, String totpCode) {}
    public record MfaChangeStartRequest(@jakarta.validation.constraints.Size(max = 128) String currentPassword) {}
    public record MfaChangeConfirmation(@jakarta.validation.constraints.Size(max = 128) String currentPassword,
                                        @Valid @jakarta.validation.constraints.NotNull
                                        LoginVerificationModels.VerifyRequest codes) {}
    @org.springframework.web.bind.annotation.ExceptionHandler(LoginVerificationService.VerificationFailure.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> mfaFailure(
            LoginVerificationService.VerificationFailure ex) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(java.util.Map.of("message", ex.getMessage()));
    }

    private ResponseStatusException badRequest(IllegalArgumentException ex) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
}
