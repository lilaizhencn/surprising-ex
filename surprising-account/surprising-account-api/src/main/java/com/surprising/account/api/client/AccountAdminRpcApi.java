package com.surprising.account.api.client;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceResponse;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "surprising-account-provider",
        contextId = "accountAdminRpcApi",
        path = AccountApiPaths.ACCOUNT_ADMIN_BASE_PATH,
        url = "${surprising.clients.account.base-url:http://localhost:9086}")
public interface AccountAdminRpcApi {

    @PostMapping("/balance-adjustments")
    BalanceResponse adjustBalance(@Valid @RequestBody BalanceAdjustmentRequest request);
}
