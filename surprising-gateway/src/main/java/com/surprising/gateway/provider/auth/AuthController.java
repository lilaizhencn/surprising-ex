package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AuthResponse;
import com.surprising.gateway.provider.auth.AuthModels.EmailVerificationRequest;
import com.surprising.gateway.provider.auth.AuthModels.LoginRequest;
import com.surprising.gateway.provider.auth.AuthModels.PasswordResetRequest;
import com.surprising.gateway.provider.auth.AuthModels.PasswordResetResponse;
import com.surprising.gateway.provider.auth.AuthModels.PasswordResetStartRequest;
import com.surprising.gateway.provider.auth.AuthModels.RefreshRequest;
import com.surprising.gateway.provider.auth.AuthModels.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        try {
            return authService.register(request, httpRequest);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @PostMapping("/verify-email")
    public boolean verifyEmail(@RequestHeader("Authorization") String authorization,
                               @Valid @RequestBody EmailVerificationRequest request) {
        try {
            return authService.verifyEmail(authorization, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    @PostMapping("/resend-email-verification")
    public EmailVerificationService.IssuedChallenge resendEmailVerification(
            @RequestHeader("Authorization") String authorization) {
        try {
            return authService.resendEmailVerification(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    @PostMapping("/forgot-password")
    public PasswordResetResponse forgotPassword(@Valid @RequestBody PasswordResetStartRequest request,
                                                HttpServletRequest httpRequest) {
        try {
            var result = passwordResetService.requestPasswordReset(
                    request.identifier(), httpRequest.getRemoteAddr(), java.time.Instant.now());
            return new PasswordResetResponse(result.accepted());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @PostMapping("/reset-password")
    public PasswordResetResponse resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        try {
            var result = passwordResetService.resetPassword(
                    request.identifier(), request.code(), request.newPassword(), java.time.Instant.now());
            if (!result.accepted()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid or expired reset code");
            }
            return new PasswordResetResponse(true);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        try {
            return authService.login(request, httpRequest);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        try {
            return authService.refresh(request, httpRequest);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/logout")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
    }

    @GetMapping("/me")
    public AuthModels.JwtPrincipal me(@RequestHeader("Authorization") String authorization) {
        try {
            return authService.authenticateBearer(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }
}
