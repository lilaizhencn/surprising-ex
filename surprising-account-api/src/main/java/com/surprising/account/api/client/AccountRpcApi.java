package com.surprising.account.api.client;

import com.surprising.product.api.ProductLine;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.ProductBalanceQueryResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-gateway", contextId = "accountRpcApi")
public interface AccountRpcApi {

    @GetMapping("/api/v1/accounts/balance")
    BalanceResponse balance(@RequestParam("userId") @Positive long userId,
                            @RequestParam("asset") @NotBlank String asset);

    @GetMapping("/api/v1/accounts/balances")
    BalanceQueryResponse balances(@RequestParam("userId") @Positive long userId);

    @GetMapping("/api/v1/accounts/product-balance")
    ProductBalanceResponse productBalance(@RequestParam("userId") @Positive long userId,
                                          @RequestParam("accountType") AccountType accountType,
                                          @RequestParam("asset") @NotBlank String asset);

    @GetMapping("/api/v1/accounts/product-balances")
    ProductBalanceQueryResponse productBalances(@RequestParam("userId") @Positive long userId,
                                                @RequestParam(value = "accountType", required = false)
                                                AccountType accountType);

    @PostMapping("/api/v1/accounts/transfers")
    ProductTransferResponse transfer(@Valid @RequestBody ProductTransferRequest request);

    @GetMapping("/api/v1/accounts/position-mode")
    PositionModeResponse positionMode(@RequestParam("userId") @Positive long userId,
                                      @RequestParam(value = "productLine", required = false)
                                      ProductLine productLine);

    default PositionModeResponse positionMode(long userId) {
        return positionMode(userId, null);
    }

    @PostMapping("/api/v1/accounts/position-mode")
    PositionModeResponse updatePositionMode(@Valid @RequestBody PositionModeUpdateRequest request);

    @GetMapping("/api/v1/accounts/position")
    PositionResponse position(@RequestParam("userId") @Positive long userId,
                              @RequestParam("instrumentId") @NotBlank String instrumentId,
                              @RequestParam(value = "marginMode", required = false) String marginMode,
                              @RequestParam(value = "positionSide", required = false) String positionSide);

    default PositionResponse position(long userId, String instrumentId, String marginMode) {
        return position(userId, instrumentId, marginMode, null);
    }

    @GetMapping("/api/v1/accounts/position-margin")
    PositionMarginResponse positionMargin(@RequestParam("userId") @Positive long userId,
                                          @RequestParam("instrumentId") @NotBlank String instrumentId,
                                          @RequestParam(value = "marginMode", required = false) String marginMode);

    @PostMapping("/api/v1/accounts/position-margin-adjustments")
    PositionMarginAdjustmentResponse adjustPositionMargin(
            @Valid @RequestBody PositionMarginAdjustmentRequest request);

    @GetMapping("/api/v1/accounts/positions")
    PositionQueryResponse positions(@RequestParam("userId") @Positive long userId,
                                    @RequestParam(value = "positionSide", required = false) String positionSide);

    default PositionQueryResponse positions(long userId) {
        return positions(userId, null);
    }
}
