package com.surprising.account.provider.controller;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.OpenInterestSnapshotResponse;
import com.surprising.account.provider.service.AccountOpenInterestSnapshotService;
import com.surprising.product.api.ProductLine;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 账户服务内部 RPC 入口，统一返回当前产品线的未平仓量快照。 */
@RestController
@RequestMapping(AccountApiPaths.INTERNAL_BASE_PATH)
public class AccountOpenInterestInternalController {

    private final AccountOpenInterestSnapshotService snapshotService;

    public AccountOpenInterestInternalController(AccountOpenInterestSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @GetMapping("/open-interest/snapshot")
    public OpenInterestSnapshotResponse snapshot(@RequestParam("productLine") ProductLine productLine) {
        try {
            return snapshotService.snapshot(productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }
}
