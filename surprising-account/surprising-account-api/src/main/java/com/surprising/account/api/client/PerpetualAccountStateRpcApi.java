package com.surprising.account.api.client;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 产品线账户完整快照唯一初始化 RPC；实时增量仍然只通过 Kafka 传播。 */
@FeignClient(
        name = "surprising-account-provider",
        contextId = "perpetualAccountStateRpcApi",
        path = AccountApiPaths.INTERNAL_BASE_PATH,
        url = "${surprising.clients.account.base-url:http://localhost:9086}")
public interface PerpetualAccountStateRpcApi {

    @GetMapping("/perpetual-state/snapshot")
    PerpetualAccountStateUpdatedEvent snapshot(@RequestParam("productLine") ProductLine productLine,
                                               @RequestParam("userId") long userId);
}
