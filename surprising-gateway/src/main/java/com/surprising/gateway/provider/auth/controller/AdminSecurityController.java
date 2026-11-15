package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.service.AdminAuthService;

import com.surprising.gateway.provider.auth.model.AuthModels.AdminMfaEnrollmentResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminMfaStatusResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminMfaVerificationRequest;
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

    private final AdminAuthService adminAuthService;

    public AdminSecurityController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @GetMapping("/mfa")
    public AdminMfaStatusResponse mfaStatus(@RequestHeader("Authorization") String authorization) {
        try {
            return adminAuthService.adminMfaStatus(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

}
