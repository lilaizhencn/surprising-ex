package com.surprising.account.provider.controller;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.PendingProductTransfersRequest;
import com.surprising.account.api.model.PendingProductTransfersResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountCommandRejectedException;
import com.surprising.account.provider.service.AccountCommandGateway;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class ProductTransferInternalController {

    private final AccountCommandGateway commands;

    private final AccountProperties properties;

    public ProductTransferInternalController(AccountCommandGateway commands, AccountProperties properties) {
        this.commands = commands;
        this.properties = properties;
    }

    @PostMapping(AccountApiPaths.TRANSFER_OUT_PATH)
    public ProductBalanceResponse transferOut(@Valid @RequestBody ProductTransferOperationRequest request) {
        return commands.transferOut(request);
    }

    @PostMapping(AccountApiPaths.TRANSFER_IN_PATH)
    public ProductBalanceResponse transferIn(@Valid @RequestBody ProductTransferOperationRequest request) {
        return commands.transferIn(request);
    }

    @PostMapping(AccountApiPaths.TRANSFER_COMPLETE_PATH)
    public void complete(@Valid @RequestBody ProductTransferOperationRequest request) {
        commands.completeTransfer(request);
    }

    @PostMapping(AccountApiPaths.TRANSFER_PENDING_PATH)
    public PendingProductTransfersResponse pending(@Valid @RequestBody PendingProductTransfersRequest request) {
        if (request.productLine() != properties.getKafka().getProductLine()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "pending transfer product line mismatch");
        }
        return new PendingProductTransfersResponse(commands.pendingTransfers(request.limit()));
    }

    @ExceptionHandler(AccountCommandRejectedException.class)
    public void rejected(AccountCommandRejectedException exception) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, exception.errorCode(), exception);
    }
}
