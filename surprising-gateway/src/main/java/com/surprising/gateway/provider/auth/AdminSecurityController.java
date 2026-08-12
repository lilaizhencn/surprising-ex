package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminMfaEnrollmentResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminMfaStatusResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminMfaVerificationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/security")
public class AdminSecurityController {

    private final AuthService authService;

    public AdminSecurityController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/mfa")
    public AdminMfaStatusResponse mfaStatus(@RequestHeader("Authorization") String authorization) {
        try {
            return authService.adminMfaStatus(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/mfa/enroll")
    public AdminMfaEnrollmentResponse enrollMfa(@RequestHeader("Authorization") String authorization) {
        try {
            return authService.enrollAdminMfa(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/mfa/confirm")
    public AdminMfaStatusResponse confirmMfa(@RequestHeader("Authorization") String authorization,
                                             @Valid @RequestBody AdminMfaVerificationRequest request) {
        try {
            return authService.confirmAdminMfa(authorization, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/mfa/disable")
    public AdminMfaStatusResponse disableMfa(@RequestHeader("Authorization") String authorization,
                                             @Valid @RequestBody(required = false) AdminMfaVerificationRequest request) {
        try {
            return authService.disableAdminMfa(authorization, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }
}
