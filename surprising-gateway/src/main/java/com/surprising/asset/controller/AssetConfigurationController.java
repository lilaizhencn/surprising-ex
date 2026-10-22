package com.surprising.asset.controller;

import com.surprising.gateway.provider.auth.controller.AdminApprovalRequestBinding;
import com.surprising.gateway.provider.exception.AdminApprovalRequiredException;
import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.service.AssetConfigurationService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/assets")
public class AssetConfigurationController {
    private final AuthService auth;
    private final AssetConfigurationService service;
    private final AdminApprovalService approvals;
    private final ObjectMapper json;
    private final com.surprising.gateway.provider.service.WalletFundingService funding;

    public AssetConfigurationController(AuthService auth, AssetConfigurationService service, AdminApprovalService approvals, ObjectMapper json, com.surprising.gateway.provider.service.WalletFundingService funding) {
        this.funding = funding;
        this.auth = auth;
        this.approvals = approvals;
        this.json = json;
        this.service = service;
    }

    @GetMapping
    public List<Asset> list(@RequestHeader("Authorization") String authorization,
                           @RequestParam(defaultValue = "false") boolean listedOnly) {
        auth.requireAdminPermission(authorization, "admin.wallet.read");
        return service.list(listedOnly);
    }

    @PostMapping
    public Asset save(@RequestHeader("Authorization") String authorization, @RequestBody byte[] body, HttpServletRequest request) {
        long operatorId = requireWrite(authorization, request, body);
        return execute(() -> service.save(validateAsset(json.readValue(body, AssetRequest.class)), operatorId));
    }

    @GetMapping("/{assetId}/networks")
    public List<Network> networks(@RequestHeader("Authorization") String authorization, @PathVariable int assetId) {
        auth.requireAdminPermission(authorization, "admin.wallet.read");
        return execute(() -> service.networks(assetId));
    }

    @PostMapping("/{assetId}/networks")
    public Network saveNetwork(@RequestHeader("Authorization") String authorization, @PathVariable int assetId,
                               @RequestBody byte[] body, HttpServletRequest request) {
        long operatorId = requireWrite(authorization, request, body);
        return execute(() -> {
            NetworkRequest config = validateNetwork(json.readValue(body, NetworkRequest.class));
            funding.validateNetwork(assetId, config);
            return service.saveNetwork(assetId, config, operatorId);
        });
    }

    private long requireWrite(String authorization, HttpServletRequest request, byte[] body) {
        try {
            return approvals.requireWrite(authorization, "admin.wallet.write", "gateway-admin",
                    new AdminRequestMetadata(AdminApprovalRequestBinding.reference(request.getHeader(approvals.approvalHeaderName())),
                            request.getMethod(), request.getRequestURI(), request.getQueryString()), body).userId();
        } catch (AdminApprovalRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        }
    }

    private <T> T execute(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (tools.jackson.core.JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid configuration body", ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "asset or network already exists, or configuration violates constraints", ex);
        }
    }

    private static AssetRequest validateAsset(AssetRequest request) {
        if (request == null) throw new IllegalArgumentException("configuration is required");
        requireText(request.reason(), "reason", 500);
        requireText(request.asset(), "asset", 20);
        if (!request.asset().matches("[A-Z0-9]{2,20}")) throw new IllegalArgumentException("invalid asset code");
        requireText(request.displayName(), "displayName", 100);
        if (request.logoUrl() == null || request.logoUrl().length() > 500
                || !(request.logoUrl().isEmpty() || request.logoUrl().startsWith("/assets/")
                || request.logoUrl().startsWith("https://"))) {
            throw new IllegalArgumentException("logoUrl must be an asset path or HTTPS URL");
        }
        return request;
    }

    private static NetworkRequest validateNetwork(NetworkRequest request) {
        if (request == null) throw new IllegalArgumentException("configuration is required");
        requireText(request.reason(), "reason", 500);
        requireText(request.networkCode(), "networkCode", 32);
        if (!request.networkCode().matches("[A-Z0-9][A-Z0-9_-]{0,31}")) throw new IllegalArgumentException("invalid network code");
        requireText(request.displayName(), "displayName", 100);
        if (request.contractAddress() == null || request.contractAddress().length() > 128
                || !request.contractAddress().equals(request.contractAddress().trim())
                || (request.nativeAsset() != request.contractAddress().isEmpty())) {
            throw new IllegalArgumentException("native asset requires empty contract; token requires a contract address");
        }
        if (request.chainDecimals() < 0 || request.chainDecimals() > 36 || request.confirmations() <= 0) {
            throw new IllegalArgumentException("invalid chain decimals or confirmations");
        }
        return request;
    }

    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.trim())) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
