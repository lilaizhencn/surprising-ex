package com.surprising.account.api.client;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-account-provider", contextId = "accountRpcApi")
@RequestMapping(AccountApiPaths.ACCOUNT_BASE_PATH)
public interface AccountRpcApi {

    @GetMapping("/balance")
    BalanceResponse balance(@RequestParam("userId") @Positive long userId,
                            @RequestParam("asset") @NotBlank String asset);

    @GetMapping("/balances")
    BalanceQueryResponse balances(@RequestParam("userId") @Positive long userId);

    @GetMapping("/position")
    PositionResponse position(@RequestParam("userId") @Positive long userId,
                              @RequestParam("symbol") @NotBlank String symbol);

    @GetMapping("/positions")
    PositionQueryResponse positions(@RequestParam("userId") @Positive long userId);
}
