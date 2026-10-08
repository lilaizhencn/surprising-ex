package com.surprising.trading.order.controller;

import com.surprising.account.provider.service.AccountService;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.trading.api.model.MakerQuoteInputs;
import com.surprising.trading.order.service.TradingFeeService;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Batch transport for one product/instrument cycle. Missing state aborts the entire response. */
@RestController
@RequestMapping("/internal/v1/trading/maker")
public class MakerQuoteInputsController {
    private final AccountService accounts;
    private final TradingFeeService fees;
    private final InstrumentSnapshotCache instruments;
    private final AccountProperties properties;

    public MakerQuoteInputsController(AccountService accounts, TradingFeeService fees,
            @Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache instruments,
            AccountProperties properties) {
        this.accounts = accounts; this.fees = fees; this.instruments = instruments; this.properties = properties;
    }

    @PostMapping("/quote-inputs")
    public MakerQuoteInputs.Response query(@RequestBody MakerQuoteInputs.Request request) {
        try {
            ProductLine product = request.productLine();
            ProductLineConfiguration.requireSame(properties.getKafka().getProductLine(), product, "maker quote inputs");
            var instrument = instruments.current(product, Integer.parseInt(request.instrumentId()), request.instrumentChangeId())
                    .orElseThrow(() -> new IllegalStateException("maker instrument version unavailable"));
            if (product == ProductLine.SPOT && (instrument.baseAsset() == null || instrument.baseAsset().isBlank()
                    || instrument.quantityStepUnits() <= 0))
                throw new IllegalStateException("spot inventory requires base asset and quantity step");
            var result = new ArrayList<MakerQuoteInputs.Account>(request.accountIds().size());
            for (long user : request.accountIds()) {
                long inventory;
                if (product == ProductLine.SPOT) {
                    var balance = accounts.balance(user, instrument.baseAsset());
                    inventory = Math.max(0L, balance.equityUnits()) / instrument.quantityStepUnits();
                } else {
                    inventory = accounts.position(user, request.instrumentId(), request.marginMode().name(), "NET")
                            .signedQuantitySteps();
                }
                var fee = fees.effectiveFee(user, request.instrumentId(), request.instrumentChangeId(), product);
                result.add(new MakerQuoteInputs.Account(user, inventory, fee.makerFeeRatePpm()));
            }
            return new MakerQuoteInputs.Response(product, request.instrumentId(), request.instrumentChangeId(), result);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage(), unavailable);
        }
    }
}
