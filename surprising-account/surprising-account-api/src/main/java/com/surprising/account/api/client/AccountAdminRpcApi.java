package com.surprising.account.api.client;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceResponse;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@FeignClient(name = "surprising-account-provider", contextId = "accountAdminRpcApi")
@RequestMapping(AccountApiPaths.ACCOUNT_ADMIN_BASE_PATH)
public interface AccountAdminRpcApi {

    @PostMapping("/balance-adjustments")
    BalanceResponse adjustBalance(@Valid @RequestBody BalanceAdjustmentRequest request);
}
