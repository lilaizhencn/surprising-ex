package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.UserMfaVerificationRequest;
import com.surprising.gateway.provider.auth.AuthModels.ChangePasswordRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminSessionRevokeResponse;
import com.surprising.gateway.provider.auth.AuthModels.LoginLogQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.RevokeOtherSessionsRequest;
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
import org.springframework.transaction.annotation.Transactional;
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
    private final GatewayUserAccessBlockRepository accessBlocks;
    private final ClientIpResolver clientIpResolver;

    public UserSecurityController(AuthService authService,
                                  UserSecurityService securityService,
                                  LoginVerificationService loginVerification,
                                  SensitiveActionVerificationService verificationService,
                                  AuthPersistenceService persistence,
                                  GatewayUserAccessBlockRepository accessBlocks,
                                  com.surprising.gateway.provider.config.GatewayProperties properties) {
        this.authService = authService;
        this.securityService = securityService;
        this.loginVerification = loginVerification;
        this.verificationService = verificationService;
        this.persistence = persistence;
        this.accessBlocks = accessBlocks;
        this.clientIpResolver = new ClientIpResolver(properties);
    }

    @GetMapping("/mfa")
    public UserSecurityService.UserMfaStatus mfaStatus(@RequestHeader("Authorization") String authorization) {
        return securityService.status(principal(authorization).userId());
    }

    @PostMapping("/mfa/enroll")
    public LoginVerificationService.BindingResponse beginMfaEnrollment(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody MfaChangeStartRequest request) {
        return loginVerification.beginBinding(principal(authorization).userId(), "TOTP",
                new LoginVerificationService.SettingRequest(request.currentPassword(), null, true), Instant.now());
    }

    @PostMapping("/mfa/confirm")
    public UserSecurityService.UserMfaStatus confirmMfaEnrollment(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody MfaChangeConfirmation request) {
        long userId = principal(authorization).userId();
        loginVerification.confirmBinding(userId, "TOTP", new LoginVerificationService.SettingConfirmation(
                request.currentPassword(), request.codes()), Instant.now());
        return securityService.status(userId);
    }

    @PostMapping("/mfa/disable")
    public LoginVerificationService.BindingResponse beginMfaDisable(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody MfaChangeStartRequest request) {
        return loginVerification.beginBinding(principal(authorization).userId(), "TOTP",
                new LoginVerificationService.SettingRequest(request.currentPassword(), null, false), Instant.now());
    }

    @PostMapping("/mfa/disable/confirm")
    public UserSecurityService.UserMfaStatus confirmMfaDisable(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody MfaChangeConfirmation request) {
        long userId = principal(authorization).userId();
        loginVerification.confirmBinding(userId, "TOTP", new LoginVerificationService.SettingConfirmation(
                request.currentPassword(), request.codes()), Instant.now());
        return securityService.status(userId);
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

    @GetMapping("/devices")
    public List<DeviceStatus> devices(@RequestHeader("Authorization") String authorization) {
        var principal = principal(authorization);
        String currentDeviceId = persistence.deviceIdForSession(principal.userId(), principal.sessionId()).orElse(null);
        return persistence.devices(principal.userId()).stream().map(device -> new DeviceStatus(
                device.sessionId(), device.deviceId(), device.userAgent(), device.ipAddress(),
                device.lastSeen(), device.active(), device.deviceId() == null
                        ? device.sessionId() == principal.sessionId()
                        : device.deviceId().equals(currentDeviceId),
                accessBlocks.blocked(principal.userId(), "DEVICE", device.deviceId()))).toList();
    }

    @PostMapping("/devices/{deviceId}/revoke")
    public AuthModels.AdminSessionRevokeResponse revokeDevice(
            @RequestHeader("Authorization") String authorization, @PathVariable String deviceId) {
        long userId = principal(authorization).userId();
        Instant now = Instant.now();
        int revoked = persistence.revokeDeviceSessions(userId, normalizedDeviceId(deviceId), now);
        return new AuthModels.AdminSessionRevokeResponse(revoked, now);
    }

    @PostMapping("/devices/{deviceId}/block")
    @Transactional
    public void blockDevice(@RequestHeader("Authorization") String authorization,
                            @PathVariable String deviceId, @RequestBody AccessChangeRequest request) {
        var principal = principal(authorization);
        String normalized = normalizedDeviceId(deviceId);
        if (normalized.equals(persistence.deviceIdForSession(principal.userId(), principal.sessionId()).orElse(null))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot block the current device");
        }
        requireSecurity(principal.userId(), request);
        Instant now = Instant.now();
        accessBlocks.block(principal.userId(), "DEVICE", normalized, now);
        persistence.revokeDeviceSessions(principal.userId(), normalized, now);
    }

    @PostMapping("/devices/{deviceId}/unblock")
    @Transactional
    public void unblockDevice(@RequestHeader("Authorization") String authorization,
                              @PathVariable String deviceId, @RequestBody AccessChangeRequest request) {
        long userId = principal(authorization).userId();
        requireSecurity(userId, request);
        accessBlocks.unblock(userId, "DEVICE", normalizedDeviceId(deviceId), Instant.now());
    }

    @GetMapping("/ips")
    public List<IpStatus> ips(@RequestHeader("Authorization") String authorization) {
        long userId = principal(authorization).userId();
        return accessBlocks.knownIps(userId).stream().map(ip -> new IpStatus(
                ip.ipAddress(), ip.loginCount(), ip.lastSeen(),
                accessBlocks.blocked(userId, "IP", ip.ipAddress()))).toList();
    }

    @PostMapping("/ips/block")
    @Transactional
    public void blockIp(@RequestHeader("Authorization") String authorization,
                        @RequestBody IpAccessChangeRequest request,
                        jakarta.servlet.http.HttpServletRequest httpRequest) {
        long userId = principal(authorization).userId();
        String ip = normalizedIp(request.ipAddress());
        if (ip.equals(clientIpResolver.resolve(httpRequest))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot block the current IP");
        }
        requireSecurity(userId, new AccessChangeRequest(request.emailCode(), request.totpCode()));
        Instant now = Instant.now();
        accessBlocks.block(userId, "IP", ip, now);
        persistence.revokeIpSessions(userId, ip, now);
    }

    @PostMapping("/ips/unblock")
    @Transactional
    public void unblockIp(@RequestHeader("Authorization") String authorization,
                          @RequestBody IpAccessChangeRequest request) {
        long userId = principal(authorization).userId();
        requireSecurity(userId, new AccessChangeRequest(request.emailCode(), request.totpCode()));
        accessBlocks.unblock(userId, "IP", normalizedIp(request.ipAddress()), Instant.now());
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

    private void requireSecurity(long userId, AccessChangeRequest request) {
        if (request == null || !verificationService.verify(userId, "SECURITY_SETTINGS",
                request.emailCode(), request.totpCode(), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "security verification is required or invalid");
        }
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
    public record MfaChangeStartRequest(@jakarta.validation.constraints.NotBlank String currentPassword) {}
    public record MfaChangeConfirmation(@jakarta.validation.constraints.NotBlank String currentPassword,
                                        @Valid @jakarta.validation.constraints.NotNull
                                        LoginVerificationService.VerifyRequest codes) {}
    public record DeviceStatus(long sessionId, String deviceId, String userAgent, String ipAddress,
                               Instant lastSeen, boolean active, boolean current, boolean blocked) {}
    public record IpStatus(String ipAddress, long loginCount, Instant lastSeen, boolean blocked) {}

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
