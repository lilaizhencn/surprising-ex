package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.service.WalletFundingService;
import com.surprising.gateway.provider.service.CustodyWalletClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class WalletFundingController {
    private final AuthService auth;
    private final WalletFundingService funding;
    private final CustodyWalletClient custody;
    public WalletFundingController(AuthService auth, WalletFundingService funding, CustodyWalletClient custody) {
        this.auth = auth; this.funding = funding; this.custody = custody;
    }
    @GetMapping("/api/v1/wallet/assets")
    public List<WalletFundingService.FundingAsset> assets(@RequestHeader("Authorization") String authorization,
            @RequestParam(defaultValue = "deposit") String operation) {
        userId(authorization);
        return execute(() -> funding.catalog(operation));
    }
    @PostMapping("/api/v1/wallet/deposit-address")
    public WalletFundingService.DepositAddress address(@RequestHeader("Authorization") String authorization,
            @Valid @RequestBody AddressRequest request) {
        long userId = userId(authorization);
        return execute(() -> funding.depositAddress(userId, request.asset(), request.network()));
    }
    @GetMapping("/api/v1/admin/assets/custody-chains")
    public List<Map<String, Object>> chains(@RequestHeader("Authorization") String authorization) {
        auth.requireAdminPermission(authorization, "admin.wallet.read");
        return execute(custody::chains);
    }
    private long userId(String authorization) {
        try { return auth.authenticateBearer(authorization).userId(); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session", ex); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account unavailable", ex); }
    }
    private <T> T execute(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex); }
    }
    public record AddressRequest(@NotBlank @Pattern(regexp="[A-Z0-9]{2,20}") String asset,
                                 @NotBlank @Pattern(regexp="[A-Z0-9][A-Z0-9_-]{0,31}") String network) {}
}
