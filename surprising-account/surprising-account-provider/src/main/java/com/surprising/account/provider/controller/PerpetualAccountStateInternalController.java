package com.surprising.account.provider.controller;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.service.PerpetualAccountStateSnapshotService;
import com.surprising.product.api.ProductLine;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 订单等下游模块启动或用户首次接入时使用的永续账户快照初始化入口。 */
@RestController
@RequestMapping(AccountApiPaths.INTERNAL_BASE_PATH)
public class PerpetualAccountStateInternalController {

    private final PerpetualAccountStateSnapshotService snapshotService;

    public PerpetualAccountStateInternalController(PerpetualAccountStateSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @GetMapping("/perpetual-state/snapshot")
    public PerpetualAccountStateUpdatedEvent snapshot(@RequestParam("productLine") ProductLine productLine,
                                                       @RequestParam("userId") long userId) {
        try {
            return snapshotService.snapshot(productLine, userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }
}
