package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.auth.model.CurrentUser;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.VipTierModels.UserVipStatusResponse;
import com.surprising.trading.order.model.VipTierModels.VipTierDefinition;
import com.surprising.trading.order.service.VipTierService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trading/fees/vip")
public class VipFeeController {

    private final VipTierService vipTierService;

    public VipFeeController(VipTierService vipTierService) {
        this.vipTierService = vipTierService;
    }

    @GetMapping("/levels")
    public List<VipTierDefinition> levels() {
        return vipTierService.getVipTiers();
    }

    @GetMapping("/my-status")
    public UserVipStatusResponse myStatus(@CurrentUser long userId) {
        return vipTierService.getUserVipStatus(userId);
    }

    @PostMapping("/evaluate")
    public UserVipStatusResponse evaluate(@CurrentUser long userId,
                                          @RequestParam(required = false) ProductLine productLine) {
        return vipTierService.evaluateAndApplyUserVip(userId, productLine);
    }
}
