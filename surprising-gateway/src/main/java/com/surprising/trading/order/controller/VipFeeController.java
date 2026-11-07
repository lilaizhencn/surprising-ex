package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.VipTierModels.UserVipStatusResponse;
import com.surprising.trading.order.model.VipTierModels.VipTierDefinition;
import com.surprising.trading.order.service.VipTierService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/trading/fees/vip")
public class VipFeeController {

    private final VipTierService vipTierService;
    private final AuthService authService;

    public VipFeeController(VipTierService vipTierService, AuthService authService) {
        this.vipTierService = vipTierService;
        this.authService = authService;
    }

    @GetMapping("/levels")
    public List<VipTierDefinition> levels() {
        return vipTierService.getVipTiers();
    }

    @GetMapping("/my-status")
    public UserVipStatusResponse myStatus(@RequestHeader("Authorization") String authorization) {
        JwtPrincipal principal = principal(authorization);
        return vipTierService.getUserVipStatus(principal.userId());
    }

    @PostMapping("/evaluate")
    public UserVipStatusResponse evaluate(@RequestHeader("Authorization") String authorization,
                                          @RequestParam(required = false) ProductLine productLine) {
        JwtPrincipal principal = principal(authorization);
        return vipTierService.evaluateAndApplyUserVip(principal.userId(), productLine);
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
}
