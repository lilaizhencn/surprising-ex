package com.surprising.account.api.client;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.OpenInterestSnapshotResponse;
import com.surprising.product.api.ProductLine;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 账户模块唯一的未平仓量初始化 RPC。 */
@FeignClient(
        name = "surprising-account-provider",
        contextId = "accountOpenInterestRpcApi",
        path = AccountApiPaths.INTERNAL_BASE_PATH,
        url = "${surprising.clients.account.base-url:http://localhost:9086}")
public interface AccountOpenInterestRpcApi {

    @GetMapping("/open-interest/snapshot")
    OpenInterestSnapshotResponse snapshot(@RequestParam("productLine") ProductLine productLine);
}
