package com.surprising.asset.controller;

import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.service.AssetConfigurationService;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.AdminApprovalService;
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
        return execute(() -> service.save(json.readValue(body, AssetRequest.class), operatorId));
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
            NetworkRequest config = json.readValue(body, NetworkRequest.class);
            funding.validateNetwork(assetId, config);
            return service.saveNetwork(assetId, config, operatorId);
        });
    }

    private long requireWrite(String authorization, HttpServletRequest request, byte[] body) {
        try {
            return approvals.requireWrite(authorization, "admin.wallet.write", "gateway-admin",
                    new AdminApprovalService.AdminRequestMetadata(request.getHeader(approvals.approvalHeaderName()),
                            request.getMethod(), request.getRequestURI(), request.getQueryString(),
                            request.getHeader("X-Trace-Id")), body).userId();
        } catch (AdminApprovalService.AdminApprovalRequiredException ex) {
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
}
