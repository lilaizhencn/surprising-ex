package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.SensitiveActionVerificationService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.CustodyWalletClient;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/wallet")
public class CustodyWalletController {

    private final AuthService authService;
    private final CustodyWalletClient walletClient;
    private final SensitiveActionVerificationService verificationService;
    private final ComplianceService complianceService;
    private final CustodyWithdrawalService withdrawalService;
    private final GatewayProperties properties;

    public CustodyWalletController(AuthService authService,
                                   CustodyWalletClient walletClient,
                                   SensitiveActionVerificationService verificationService,
                                   ComplianceService complianceService,
                                   CustodyWithdrawalService withdrawalService,
                                   GatewayProperties properties) {
        this.authService = authService;
        this.walletClient = walletClient;
        this.verificationService = verificationService;
        this.complianceService = complianceService;
        this.withdrawalService = withdrawalService;
        this.properties = properties;
    }

    @PostMapping("/addresses")
    public Map<String, Object> createAddress(@RequestHeader("Authorization") String authorization,
                                             @Valid @RequestBody CreateAddressRequest request) {
        try {
            JwtPrincipal principal = principal(authorization);
            return walletClient.createAddress(principal.userId(), request.chain(), request.addressVersion());
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @GetMapping("/chains")
    public List<Map<String, Object>> chains(@RequestHeader("Authorization") String authorization) {
        try {
            principal(authorization);
            return walletClient.chains();
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @GetMapping("/deposits")
    public List<Map<String, Object>> deposits(@RequestHeader("Authorization") String authorization,
                                              @RequestParam(required = false) String chain,
                                              @RequestParam(required = false) String asset,
                                              @RequestParam(defaultValue = "50") int limit) {
        try {
            return walletClient.deposits(principal(authorization).userId(), chain, asset, limit);
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @GetMapping("/withdrawals")
    public List<Map<String, Object>> withdrawals(@RequestHeader("Authorization") String authorization,
                                                 @RequestParam(required = false) String chain,
                                                 @RequestParam(required = false) String asset,
                                                 @RequestParam(defaultValue = "50") int limit) {
        try {
            return withdrawalService.history(principal(authorization).userId(), chain, asset, limit);
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @PostMapping("/withdrawals")
    public Map<String, Object> createWithdrawal(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Security-Email-Code", required = false) String emailCode,
            @RequestHeader(value = "X-Security-TOTP-Code", required = false) String totpCode,
            @Valid @RequestBody CreateWithdrawalRequest request) {
        try {
            JwtPrincipal principal = principal(authorization);
            requireSecurity(principal.userId(), "WITHDRAWAL", emailCode, totpCode);
            requireKyc(principal.userId());
            complianceService.requireWithdrawalEligibility(principal.userId());
            java.util.UUID configuredSourceAddressId = configuredSourceAddress(request.chain());
            if (request.custodyAddressId() != null
                    && !configuredSourceAddressId.equals(request.custodyAddressId())) {
                throw new IllegalArgumentException("withdrawal source address does not match configured custody address");
            }
            CustodyWithdrawalService.WithdrawalResponse response = withdrawalService.submit(
                    principal.userId(), idempotencyKey,
                    new CustodyWithdrawalService.WithdrawalRequest(
                            configuredSourceAddressId, request.chain(), request.assetSymbol(), request.toAddress(),
                            request.amount(), request.externalReference()));
            return Map.of("id", response.walletWithdrawalId() == null
                            ? response.withdrawalId().toString() : response.walletWithdrawalId(),
                    "withdrawalId", response.withdrawalId().toString(), "status", response.status(),
                    "success", true);
        } catch (CustodyWithdrawalService.WithdrawalRejectedException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (CustodyWithdrawalService.WithdrawalUnknownException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    private void requireKyc(long userId) {
        KycProfile profile = complianceService.kyc(userId);
        if (profile == null || !"VERIFIED".equalsIgnoreCase(profile.status())
                || (profile.expiresAt() != null && profile.expiresAt().isBefore(java.time.Instant.now()))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "verified KYC is required for withdrawals");
        }
    }

    private void requireSecurity(long userId, String scene, String emailCode, String totpCode) {
        if (!verificationService.verify(userId, scene, emailCode, totpCode, java.time.Instant.now())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "security verification is required or invalid");
        }
    }

    private java.util.UUID configuredSourceAddress(String chain) {
        String sourceId = properties.getCustodyWallet().getWithdrawalAddressIds().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(chain))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "withdrawal source address is not configured for network"));
        try {
            return java.util.UUID.fromString(sourceId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("configured withdrawal source address is invalid", ex);
        }
    }

    private JwtPrincipal principal(String authorization) {
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

    public record CreateAddressRequest(@NotBlank @Size(max = 32) String chain,
                                       @Positive Long addressVersion) {
    }

    public record CreateWithdrawalRequest(java.util.UUID custodyAddressId,
                                          @NotBlank @Size(max = 32) String chain,
                                          @NotBlank @Size(max = 32) String assetSymbol,
                                          @NotBlank @Size(max = 160) String toAddress,
                                          @NotBlank @Size(max = 120) String amount,
                                          @Size(max = 160) String externalReference) {
    }
}
