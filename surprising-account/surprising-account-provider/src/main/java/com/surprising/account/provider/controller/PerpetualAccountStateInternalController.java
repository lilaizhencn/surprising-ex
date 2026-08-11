package com.surprising.account.provider.controller;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.service.PerpetualAccountStateSnapshotService;
import com.surprising.account.provider.service.AccountUserStateReducer;
import com.surprising.account.provider.service.AccountUserStateCommandWorker;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.product.api.ProductLine;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final AccountUserStateCommandWorker stateWorker;

    public PerpetualAccountStateInternalController(PerpetualAccountStateSnapshotService snapshotService,
                                                   AccountUserStateReducer stateReducer,
                                                   AccountUserStateCommandWorker stateWorker) {
        this.snapshotService = snapshotService;
        this.stateReducer = stateReducer;
        this.stateWorker = stateWorker;
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

    /**
     * 将现有账户快照重新发布到事实流，供订单等下游服务恢复 JVM 缓存。
     *
     * <p>该操作不创建余额、不改变账户修订号，也不会绕过资金校验。</p>
     */
    @PostMapping("/perpetual-state/recover")
    public PerpetualAccountStateUpdatedEvent recover(@RequestParam("productLine") ProductLine productLine,
                                                      @RequestParam("userId") long userId) {
        PerpetualAccountStateUpdatedEvent snapshot = snapshot(productLine, userId);
        stateWorker.publishStateSnapshotForRecovery(snapshot);
        return snapshot;
    }
}
