package com.surprising.account.provider.controller;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.service.PerpetualAccountStateSnapshotService;
import com.surprising.account.provider.service.AccountUserStateReducer;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.product.api.ProductLine;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 订单等下游模块启动或用户首次接入时使用的产品线账户快照初始化入口。 */
@RestController
@RequestMapping(AccountApiPaths.INTERNAL_BASE_PATH)
public class PerpetualAccountStateInternalController {

    private final PerpetualAccountStateSnapshotService snapshotService;
    private final AccountUserStateReducer stateReducer;

    public PerpetualAccountStateInternalController(PerpetualAccountStateSnapshotService snapshotService,
                                                   AccountUserStateReducer stateReducer) {
        this.snapshotService = snapshotService;
        this.stateReducer = stateReducer;
    }

    @GetMapping("/perpetual-state/snapshot")
    public PerpetualAccountStateUpdatedEvent snapshot(@RequestParam("productLine") ProductLine productLine,
                                                       @RequestParam("userId") long userId) {
        try {
            var local = stateReducer.snapshot(new UserPartitionKey(productLine, userId));
            if (local.isPresent()) {
                return local.get();
            }
            // 只有显式初始化入口才允许从数据库恢复基线，并立即写入本地 reducer；
            // 账户命令执行器不会在热路径隐式查库。所有产品线都使用同一条边界。
            var snapshot = snapshotService.snapshot(productLine, userId);
            stateReducer.initialize(snapshot);
            return snapshot;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }
}
