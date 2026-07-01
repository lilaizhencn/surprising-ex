package com.surprising.account.provider.service;

import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.OrderFeeSnapshot;
import com.surprising.account.provider.model.PositionChange;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountRepository;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final PositionCalculator positionCalculator;
    private final ReduceOnlyOrderPruner reduceOnlyOrderPruner;
    private final AccountProperties properties;
    private final AccountOutboxRepository outboxRepository;

    public AccountService(AccountRepository accountRepository, PositionCalculator positionCalculator) {
        this(accountRepository, positionCalculator, null, new AccountProperties(), null);
    }

    @Autowired
    public AccountService(AccountRepository accountRepository,
                          PositionCalculator positionCalculator,
                          ReduceOnlyOrderPruner reduceOnlyOrderPruner,
                          AccountProperties properties,
                          AccountOutboxRepository outboxRepository) {
        this.accountRepository = accountRepository;
        this.positionCalculator = positionCalculator;
        this.reduceOnlyOrderPruner = reduceOnlyOrderPruner;
        this.properties = properties;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public BalanceResponse adjustBalance(BalanceAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        return accountRepository.adjustBalance(request.userId(), normalizeAsset(request.asset()), request.amountUnits(),
                normalizeReferenceId(request.referenceId()), request.reason());
    }

    public BalanceResponse balance(long userId, String asset) {
        return accountRepository.balance(userId, normalizeAsset(asset))
                .orElse(new BalanceResponse(userId, normalizeAsset(asset), 0L, 0L, 0L, Instant.EPOCH));
    }

    public BalanceQueryResponse balances(long userId) {
        List<BalanceResponse> rows = accountRepository.balances(userId);
        return new BalanceQueryResponse(rows.size(), rows);
    }

    public PositionResponse position(long userId, String symbol) {
        return position(userId, symbol, null);
    }

    public PositionResponse position(long userId, String symbol, String marginMode) {
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        return accountRepository.position(userId, normalizedSymbol, normalizedMarginMode)
                .orElse(new PositionResponse(userId, normalizedSymbol, 0L, normalizedMarginMode,
                        0L, 0L, 0L, Instant.EPOCH));
    }

    public PositionQueryResponse positions(long userId) {
        List<PositionResponse> rows = accountRepository.positions(userId);
        return new PositionQueryResponse(rows.size(), rows);
    }

    @Transactional
    public void processTrade(MatchTradeEvent trade) {
        if (!accountRepository.markTradeProcessing(trade.tradeId(), trade.symbol())) {
            return;
        }
        String traceId = trade.traceId();
        applyTradeSide(trade.tradeId(), trade.takerOrderId(), trade.takerUserId(), trade.symbol(),
                trade.takerInstrumentVersion(), trade.takerSide(), trade.takerMarginMode(), trade.priceTicks(), trade.quantitySteps(),
                trade.takerOrderCompleted(), true, trade.eventTime(), traceId);
        applyTradeSide(trade.tradeId(), trade.makerOrderId(), trade.makerUserId(), trade.symbol(),
                trade.makerInstrumentVersion(), opposite(trade.takerSide()), trade.makerMarginMode(), trade.priceTicks(), trade.quantitySteps(),
                trade.makerOrderCompleted(), false, trade.eventTime(), traceId);
    }

    private PositionResponse applyTradeSide(long tradeId,
                                            long orderId,
                                            long userId,
                                            String symbol,
                                            long fillInstrumentVersion,
                                            OrderSide side,
                                            MarginMode marginMode,
                                            long priceTicks,
                                            long quantitySteps,
                                            boolean orderCompleted,
                                            boolean taker,
                                            Instant eventTime,
                                            String traceId) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        ContractSpec fillSpec = accountRepository.contractSpec(symbol, fillInstrumentVersion);
        PositionState current = accountRepository.lockPosition(userId, symbol, normalizedMarginMode);
        ContractSpec positionSpec = current.signedQuantitySteps() == 0
                ? fillSpec
                : accountRepository.contractSpec(symbol, current.instrumentVersion());
        long closeSteps = MarginTransferMath.closeSteps(current.signedQuantitySteps(), side, quantitySteps);
        long openSteps = Math.subtractExact(quantitySteps, closeSteps);
        // Close first: old position margin can be returned before any flipped remainder becomes new exposure.
        if (closeSteps > 0) {
            accountRepository.releasePositionMargin(userId, symbol, normalizedMarginMode, closeSteps,
                    Math.absExact(current.signedQuantitySteps()), eventTime);
            accountRepository.releaseOrderMargin(orderId, userId, symbol, closeSteps,
                    orderCompleted && openSteps == 0, eventTime);
        }
        // Open second: filled opening quantity moves order-reserved margin into position margin accounting.
        if (openSteps > 0) {
            long actualMarginUnits = MarginTransferMath.openingInitialMarginUnits(fillSpec, priceTicks, openSteps);
            accountRepository.consumeOrderMargin(orderId, userId, symbol, normalizedMarginMode, openSteps, actualMarginUnits,
                    orderCompleted, eventTime);
        }
        PositionChange change = positionCalculator.apply(current, side, priceTicks, quantitySteps,
                positionSpec, fillSpec);
        accountRepository.settleRealizedPnl(userId, positionSpec.settleAsset(), orderId, tradeId,
                change.realizedPnlDeltaUnits(), eventTime);
        OrderFeeSnapshot feeSnapshot = accountRepository.orderFeeSnapshot(orderId, userId, symbol);
        long feeRatePpm = taker ? feeSnapshot.takerFeeRatePpm() : feeSnapshot.makerFeeRatePpm();
        long feeDeltaUnits = TradeFeeMath.feeDeltaUnits(fillSpec, priceTicks, quantitySteps, feeRatePpm);
        accountRepository.settleTradeFee(userId, fillSpec.settleAsset(), orderId, tradeId, feeDeltaUnits,
                taker ? "TAKER_FEE" : "MAKER_FEE", feeRatePpm, symbol, eventTime);
        PositionResponse updated = accountRepository.updatePosition(userId, symbol, normalizedMarginMode,
                change.next(), eventTime);
        if (reduceOnlyOrderPruner != null) {
            reduceOnlyOrderPruner.prune(userId, symbol, change.next(), eventTime, traceId);
        }
        if (outboxRepository != null) {
            outboxRepository.enqueuePositionUpdated(properties.getKafka().getPositionEventsTopic(),
                    tradeId, updated, eventTime, traceId);
        }
        return updated;
    }

    private OrderSide opposite(OrderSide side) {
        return side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    }

    private String normalizeAsset(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String normalized = asset.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + asset);
        }
        return normalized;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }

    private MarginMode normalizeMarginMode(String marginMode) {
        if (marginMode == null || marginMode.isBlank()) {
            return MarginMode.CROSS;
        }
        try {
            return MarginMode.valueOf(marginMode.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid marginMode: " + marginMode, ex);
        }
    }

    private String normalizeReferenceId(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("referenceId is required");
        }
        String normalized = referenceId.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("referenceId length must be <= 128");
        }
        return normalized;
    }
}
