package com.surprising.account.provider.controller;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.service.AccountAeronGateway;
import com.surprising.aeron.protocol.CoreReservationView;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(AccountApiPaths.INTERNAL_BASE_PATH)
public class PerpetualAccountStateInternalController {

    private final AccountAeronGateway aeron;

    public PerpetualAccountStateInternalController(AccountAeronGateway aeron) {
        this.aeron = aeron;
    }

    @GetMapping("/perpetual-state/snapshot")
    public PerpetualAccountStateUpdatedEvent snapshot(@RequestParam("productLine") ProductLine productLine,
                                                       @RequestParam("userId") long userId) {
        try {
            var state = aeron.userState(userId);
            if (state == null || state.productLine() != productLine) {
                throw new IllegalStateException("Aeron user state does not exist for product line");
            }
            Instant now = Instant.now();
            var balances = state.balances().stream().map(value -> new PerpetualAccountStateUpdatedEvent.Balance(
                    value.asset(), value.availableUnits(), value.lockedUnits())).toList();
            var positions = state.positions().stream().map(value -> new PerpetualAccountStateUpdatedEvent.Position(
                    value.symbol(), value.instrumentVersion(), MarginMode.valueOf(value.marginMode().name()),
                    PositionSide.valueOf(value.positionSide().name()), value.signedQuantitySteps(),
                    value.entryPriceTicks(), value.entryValueTicks(), value.realizedPnlUnits(), now)).toList();
            var margins = state.positions().stream().map(value -> new PerpetualAccountStateUpdatedEvent.PositionMargin(
                    value.symbol(), value.marginAsset(), MarginMode.valueOf(value.marginMode().name()),
                    PositionSide.valueOf(value.positionSide().name()), value.positionMarginUnits())).toList();
            var locks = state.reservations().stream().collect(java.util.stream.Collectors.groupingBy(
                            CoreReservationView::asset, java.util.TreeMap::new,
                    java.util.stream.Collectors.summingLong(value -> Math.subtractExact(value.reservedUnits(),
                            Math.addExact(value.releasedUnits(), value.consumedUnits())))))
                    .entrySet().stream().map(entry -> new PerpetualAccountStateUpdatedEvent.OrderLock(
                            entry.getKey(), entry.getValue())).toList();
            long revision = Math.max(1L, state.revision());
            return new PerpetualAccountStateUpdatedEvent(
                    PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, revision, revision, productLine, userId,
                    accountType(productLine).name(), balances, List.of(), positions, margins, locks,
                    PositionMode.valueOf(state.positionMode().name()), now, "aeron-query");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    @PostMapping("/perpetual-state/recover")
    public PerpetualAccountStateUpdatedEvent recover(@RequestParam("productLine") ProductLine productLine,
                                                      @RequestParam("userId") long userId) {
        return snapshot(productLine, userId);
    }

    private static AccountType accountType(ProductLine productLine) {
        return switch (productLine) {
            case SPOT -> AccountType.SPOT;
            case LINEAR_PERPETUAL -> AccountType.USDT_PERPETUAL;
            case INVERSE_PERPETUAL -> AccountType.COIN_PERPETUAL;
            case LINEAR_DELIVERY -> AccountType.USDT_DELIVERY;
            case INVERSE_DELIVERY -> AccountType.COIN_DELIVERY;
            case OPTION -> AccountType.OPTION;
        };
    }
}
