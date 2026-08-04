package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.UserMfaVerificationRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/security")
public class UserSecurityController {

    private final AuthService authService;
    private final UserSecurityService securityService;
    private final SensitiveActionVerificationService verificationService;

    public UserSecurityController(AuthService authService,
                                  UserSecurityService securityService,
                                  SensitiveActionVerificationService verificationService) {
        this.authService = authService;
        this.securityService = securityService;
        this.verificationService = verificationService;
    }

    @GetMapping("/mfa")
    public UserSecurityService.UserMfaStatus mfaStatus(@RequestHeader("Authorization") String authorization) {
        return securityService.status(principal(authorization).userId());
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
            return securityService.updateScene(principal(authorization).userId(), sceneCode,
                    request.enabled(), request.totpCode());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        }
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
