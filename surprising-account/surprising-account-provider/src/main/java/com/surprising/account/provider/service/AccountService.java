package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminBalanceAdjustmentQueryResponse;
import com.surprising.account.api.model.AccountLedgerQueryResponse;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductBalanceQueryResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductLedgerQueryResponse;
import com.surprising.account.api.model.ProductTransferRecordQueryResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.LiquidationFeeContext;
import com.surprising.account.provider.model.LiquidationFeeSettlement;
import com.surprising.account.provider.model.OrderFeeSnapshot;
import com.surprising.account.provider.model.PositionChange;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountRepository;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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
    private final BoundedLocalCache<ContractSpecKey, ContractSpec> contractSpecCache;
    private final BoundedLocalCache<OrderFeeSnapshotKey, OrderFeeSnapshot> orderFeeSnapshotCache;

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
        AccountProperties.Cache cacheProperties = properties.getCache() == null
                ? new AccountProperties.Cache()
                : properties.getCache();
        this.contractSpecCache = new BoundedLocalCache<>(cacheProperties.getContractSpecMaxEntries());
        this.orderFeeSnapshotCache = new BoundedLocalCache<>(cacheProperties.getOrderFeeSnapshotMaxEntries());
    }

    @Transactional
    public BalanceResponse adjustBalance(BalanceAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        return accountRepository.adjustBalance(request.userId(), normalizeAsset(request.asset()), request.amountUnits(),
                normalizeReferenceId(request.referenceId()), request.reason());
    }

    @Transactional
    public BalanceResponse adminAdjustBalance(String adminUserId,
                                              String adminUsername,
                                              BalanceAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        long normalizedAdminUserId = normalizeAdminUserId(adminUserId);
        String normalizedAsset = normalizeAsset(request.asset());
        String normalizedReferenceId = normalizeReferenceId(request.referenceId());
        BalanceResponse response = accountRepository.adjustBalance(request.userId(), normalizedAsset,
                request.amountUnits(), normalizedReferenceId, request.reason());
        accountRepository.recordAdminBalanceAdjustment("BASIC", normalizedAdminUserId,
                normalizeAdminUsername(adminUsername), request.userId(), null, normalizedAsset, request.amountUnits(),
                response.availableUnits(), normalizedReferenceId, request.reason());
        return response;
    }

    public BalanceResponse balance(long userId, String asset) {
        return accountRepository.balance(userId, normalizeAsset(asset))
                .orElse(new BalanceResponse(userId, normalizeAsset(asset), 0L, 0L, 0L, Instant.EPOCH));
    }

    public BalanceQueryResponse balances(long userId) {
        List<BalanceResponse> rows = accountRepository.balances(userId);
        return new BalanceQueryResponse(rows.size(), rows);
    }

    @Transactional
    public ProductBalanceResponse adjustProductBalance(ProductBalanceAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        AccountType accountType = normalizeAccountType(request.accountType());
        return accountRepository.adjustProductBalance(request.userId(), accountType, normalizeAsset(request.asset()),
                request.amountUnits(), normalizeReferenceId(request.referenceId()), request.reason());
    }

    @Transactional
    public ProductBalanceResponse adminAdjustProductBalance(String adminUserId,
                                                            String adminUsername,
                                                            ProductBalanceAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        long normalizedAdminUserId = normalizeAdminUserId(adminUserId);
        AccountType accountType = normalizeAccountType(request.accountType());
        String normalizedAsset = normalizeAsset(request.asset());
        String normalizedReferenceId = normalizeReferenceId(request.referenceId());
        ProductBalanceResponse response = accountRepository.adjustProductBalance(request.userId(), accountType,
                normalizedAsset, request.amountUnits(), normalizedReferenceId, request.reason());
        accountRepository.recordAdminBalanceAdjustment("PRODUCT", normalizedAdminUserId,
                normalizeAdminUsername(adminUsername), request.userId(), accountType, normalizedAsset,
                request.amountUnits(), response.availableUnits(), normalizedReferenceId, request.reason());
        return response;
    }

    public ProductBalanceResponse productBalance(long userId, AccountType accountType, String asset) {
        AccountType normalizedType = normalizeAccountType(accountType);
        String normalizedAsset = normalizeAsset(asset);
        return accountRepository.productBalance(userId, normalizedType, normalizedAsset)
                .orElse(new ProductBalanceResponse(userId, normalizedType, normalizedAsset, 0L, 0L, 0L,
                        Instant.EPOCH));
    }

    public ProductBalanceQueryResponse productBalances(long userId, AccountType accountType) {
        List<ProductBalanceResponse> rows = accountRepository.productBalances(userId, accountType);
        return new ProductBalanceQueryResponse(rows.size(), rows);
    }

    public AccountLedgerQueryResponse accountLedger(Long userId, String asset, String referenceType, int limit) {
        return accountLedger(userId, asset, referenceType, limit, null, null);
    }

    public AccountLedgerQueryResponse accountLedger(Long userId,
                                                    String asset,
                                                    String referenceType,
                                                    int limit,
                                                    String cursor,
                                                    String sort) {
        requireOptionalUserId(userId);
        int safeLimit = normalizeLimit(limit);
        var page = accountRepository.accountLedgerPage(userId, normalizeOptionalAsset(asset),
                normalizeOptionalReferenceType(referenceType), safeLimit, cursor, sort);
        return new AccountLedgerQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public ProductLedgerQueryResponse productLedger(Long userId,
                                                    AccountType accountType,
                                                    String asset,
                                                    String referenceType,
                                                    int limit) {
        return productLedger(userId, accountType, asset, referenceType, limit, null, null);
    }

    public ProductLedgerQueryResponse productLedger(Long userId,
                                                    AccountType accountType,
                                                    String asset,
                                                    String referenceType,
                                                    int limit,
                                                    String cursor,
                                                    String sort) {
        requireOptionalUserId(userId);
        int safeLimit = normalizeLimit(limit);
        var page = accountRepository.productLedgerPage(userId, accountType, normalizeOptionalAsset(asset),
                normalizeOptionalReferenceType(referenceType), safeLimit, cursor, sort);
        return new ProductLedgerQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public ProductTransferRecordQueryResponse productTransfers(Long userId,
                                                               AccountType accountType,
                                                               String asset,
                                                               int limit) {
        return productTransfers(userId, accountType, asset, limit, null, null);
    }

    public ProductTransferRecordQueryResponse productTransfers(Long userId,
                                                               AccountType accountType,
                                                               String asset,
                                                               int limit,
                                                               String cursor,
                                                               String sort) {
        requireOptionalUserId(userId);
        int safeLimit = normalizeLimit(limit);
        var page = accountRepository.productTransferPage(userId, accountType, normalizeOptionalAsset(asset),
                safeLimit, cursor, sort);
        return new ProductTransferRecordQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public AdminBalanceAdjustmentQueryResponse adminBalanceAdjustments(Long adminUserId,
                                                                       Long userId,
                                                                       String adjustmentKind,
                                                                       AccountType accountType,
                                                                       String asset,
                                                                       String referenceId,
                                                                       int limit) {
        return adminBalanceAdjustments(adminUserId, userId, adjustmentKind, accountType, asset, referenceId,
                limit, null, null);
    }

    public AdminBalanceAdjustmentQueryResponse adminBalanceAdjustments(Long adminUserId,
                                                                       Long userId,
                                                                       String adjustmentKind,
                                                                       AccountType accountType,
                                                                       String asset,
                                                                       String referenceId,
                                                                       int limit,
                                                                       String cursor,
                                                                       String sort) {
        requireOptionalUserId(adminUserId);
        requireOptionalUserId(userId);
        int safeLimit = normalizeLimit(limit);
        var page = accountRepository.adminBalanceAdjustmentPage(adminUserId, userId,
                normalizeOptionalAdjustmentKind(adjustmentKind), accountType, normalizeOptionalAsset(asset),
                normalizeOptionalReferenceId(referenceId), safeLimit, cursor, sort);
        return new AdminBalanceAdjustmentQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    @Transactional
    public ProductTransferResponse transfer(ProductTransferRequest request) {
        AccountType source = normalizeAccountType(request.sourceAccountType());
        AccountType target = normalizeAccountType(request.targetAccountType());
        if (source == target) {
            throw new IllegalArgumentException("sourceAccountType and targetAccountType must be different");
        }
        return accountRepository.transferProductBalance(request.userId(), source, target, normalizeAsset(request.asset()),
                request.amountUnits(), normalizeReferenceId(request.referenceId()), request.reason());
    }

    public PositionResponse position(long userId, String symbol) {
        return position(userId, symbol, null, null);
    }

    public PositionResponse position(long userId, String symbol, String marginMode) {
        return position(userId, symbol, marginMode, null);
    }

    public PositionResponse position(long userId, String symbol, String marginMode, String positionSide) {
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        normalizePositionSide(positionSide);
        return accountRepository.position(userId, normalizedSymbol, normalizedMarginMode)
                .orElse(new PositionResponse(userId, normalizedSymbol, 0L, normalizedMarginMode,
                        0L, 0L, 0L, Instant.EPOCH));
    }

    public PositionMarginResponse positionMargin(long userId, String symbol, String marginMode) {
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        return accountRepository.positionMargin(userId, normalizedSymbol, normalizedMarginMode)
                .orElse(new PositionMarginResponse(userId, normalizedSymbol, "", normalizedMarginMode,
                        0L, Instant.EPOCH));
    }

    public PositionQueryResponse positions(long userId) {
        return positions(userId, null);
    }

    public PositionQueryResponse positions(long userId, String positionSide) {
        normalizePositionSide(positionSide);
        List<PositionResponse> rows = accountRepository.positions(userId);
        return new PositionQueryResponse(rows.size(), rows);
    }

    @Transactional
    public PositionMarginAdjustmentResponse adjustPositionMargin(PositionMarginAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        String symbol = normalizeSymbol(request.symbol());
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        if (marginMode != MarginMode.ISOLATED) {
            throw new IllegalArgumentException("position margin adjustment only supports ISOLATED margin mode");
        }
        PositionMarginAdjustmentResponse response = accountRepository.adjustIsolatedPositionMargin(
                request.userId(), symbol, request.amountUnits(),
                normalizeReferenceId(request.referenceId()), normalizeReason(request.reason(), request.amountUnits()),
                properties.getPositionMargin().getMaxRiskSnapshotAge(),
                properties.getPositionMargin().getRemovalBufferPpm());
        if (outboxRepository != null) {
            PositionResponse current = accountRepository.position(request.userId(), symbol, MarginMode.ISOLATED)
                    .orElseThrow(() -> new IllegalStateException("isolated position missing after margin adjustment"));
            // Manual margin changes do not have a trade id; tradeId=0 tells downstream consumers this is a state trigger.
            outboxRepository.enqueuePositionUpdated(properties.getKafka().getPositionEventsTopic(),
                    0L, current, Instant.now(), TraceContext.currentOrCreate());
        }
        return response;
    }

    @Transactional
    public void processTrade(MatchTradeEvent trade) {
        processTradeIfNew(trade);
    }

    @Transactional
    public boolean processTradeIfNew(MatchTradeEvent trade) {
        if (!accountRepository.markTradeProcessing(trade.tradeId(), trade.symbol())) {
            return false;
        }
        String traceId = trade.traceId();
        InstrumentType takerInstrumentType = accountRepository.instrumentType(trade.symbol(),
                trade.takerInstrumentVersion());
        InstrumentType makerInstrumentType = accountRepository.instrumentType(trade.symbol(),
                trade.makerInstrumentVersion());
        if (takerInstrumentType != makerInstrumentType) {
            throw new IllegalStateException("matched orders use different instrument types for " + trade.symbol());
        }
        if (takerInstrumentType == InstrumentType.SPOT) {
            applySpotTradeSide(trade.tradeId(), trade.takerOrderId(), trade.takerUserId(), trade.symbol(),
                    trade.takerInstrumentVersion(), trade.takerSide(), trade.priceTicks(), trade.quantitySteps(),
                    trade.takerOrderCompleted(), true, trade.eventTime());
            applySpotTradeSide(trade.tradeId(), trade.makerOrderId(), trade.makerUserId(), trade.symbol(),
                    trade.makerInstrumentVersion(), opposite(trade.takerSide()), trade.priceTicks(),
                    trade.quantitySteps(), trade.makerOrderCompleted(), false, trade.eventTime());
            return true;
        }
        applyTradeSide(trade.tradeId(), trade.takerOrderId(), trade.takerUserId(), trade.symbol(),
                trade.takerInstrumentVersion(), trade.takerSide(), trade.takerMarginMode(), trade.priceTicks(), trade.quantitySteps(),
                trade.takerOrderCompleted(), true, trade.eventTime(), traceId);
        applyTradeSide(trade.tradeId(), trade.makerOrderId(), trade.makerUserId(), trade.symbol(),
                trade.makerInstrumentVersion(), opposite(trade.takerSide()), trade.makerMarginMode(), trade.priceTicks(), trade.quantitySteps(),
                trade.makerOrderCompleted(), false, trade.eventTime(), traceId);
        return true;
    }

    private void applySpotTradeSide(long tradeId,
                                    long orderId,
                                    long userId,
                                    String symbol,
                                    long instrumentVersion,
                                    OrderSide side,
                                    long priceTicks,
                                    long quantitySteps,
                                    boolean orderCompleted,
                                    boolean taker,
                                    Instant eventTime) {
        SpotInstrumentSpec spec = accountRepository.spotInstrumentSpec(symbol, instrumentVersion);
        OrderFeeSnapshot feeSnapshot = orderFeeSnapshot(orderId, userId, symbol);
        long feeRatePpm = taker ? feeSnapshot.takerFeeRatePpm() : feeSnapshot.makerFeeRatePpm();
        accountRepository.settleSpotTradeSide(userId, orderId, tradeId, symbol, side, priceTicks,
                quantitySteps, spec, feeRatePpm, taker ? "TAKER_FEE" : "MAKER_FEE", orderCompleted, eventTime);
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
        ContractSpec fillSpec = contractSpec(symbol, fillInstrumentVersion);
        PositionState current = accountRepository.lockPosition(userId, symbol, normalizedMarginMode);
        ContractSpec positionSpec = current.signedQuantitySteps() == 0
                ? fillSpec
                : contractSpec(symbol, current.instrumentVersion());
        long closeSteps = MarginTransferMath.closeSteps(current.signedQuantitySteps(), side, quantitySteps);
        long openSteps = Math.subtractExact(quantitySteps, closeSteps);
        PositionChange change = positionCalculator.apply(current, side, priceTicks, quantitySteps,
                positionSpec, fillSpec);
        if (closeSteps > 0) {
            accountRepository.settleRealizedPnl(perpetualAccountType(positionSpec), userId,
                    positionSpec.settleAsset(), orderId, tradeId, symbol, normalizedMarginMode,
                    change.realizedPnlDeltaUnits(), eventTime);
        }
        if (openSteps > 0) {
            long actualMarginUnits = MarginTransferMath.openingInitialMarginUnits(fillSpec, priceTicks, openSteps);
            accountRepository.consumeOrderMargin(orderId, userId, symbol, normalizedMarginMode, openSteps, actualMarginUnits,
                    orderCompleted, eventTime);
        }
        OrderFeeSnapshot feeSnapshot = orderFeeSnapshot(orderId, userId, symbol);
        long feeRatePpm = taker ? feeSnapshot.takerFeeRatePpm() : feeSnapshot.makerFeeRatePpm();
        long feeDeltaUnits = TradeFeeMath.feeDeltaUnits(fillSpec, priceTicks, quantitySteps, feeRatePpm);
        accountRepository.settleTradeFee(perpetualAccountType(fillSpec), userId, fillSpec.settleAsset(),
                orderId, tradeId, feeDeltaUnits, taker ? "TAKER_FEE" : "MAKER_FEE", feeRatePpm, symbol,
                normalizedMarginMode, eventTime);
        if (closeSteps > 0) {
            settleLiquidationFeeIfNeeded(tradeId, orderId, userId, symbol, normalizedMarginMode, fillSpec,
                    priceTicks, quantitySteps, eventTime, traceId);
        }
        if (closeSteps > 0) {
            accountRepository.releasePositionMargin(userId, symbol, normalizedMarginMode, closeSteps,
                    Math.absExact(current.signedQuantitySteps()), eventTime);
            accountRepository.releaseOrderMargin(orderId, userId, symbol, closeSteps,
                    orderCompleted && openSteps == 0, eventTime);
        }
        PositionResponse updated = accountRepository.updatePosition(userId, symbol, normalizedMarginMode,
                change.next(), current.signedQuantitySteps(), eventTime);
        if (reduceOnlyOrderPruner != null) {
            reduceOnlyOrderPruner.prune(userId, symbol, change.next(), eventTime, traceId);
        }
        if (outboxRepository != null) {
            outboxRepository.enqueuePositionUpdated(properties.getKafka().getPositionEventsTopic(),
                    tradeId, updated, eventTime, traceId);
        }
        return updated;
    }

    private void settleLiquidationFeeIfNeeded(long tradeId,
                                              long orderId,
                                              long userId,
                                              String symbol,
                                              MarginMode marginMode,
                                              ContractSpec fillSpec,
                                              long priceTicks,
                                              long quantitySteps,
                                              Instant eventTime,
                                              String traceId) {
        accountRepository.liquidationFeeContext(orderId, userId, symbol).ifPresent(context -> {
            long requestedFeeUnits = liquidationFeeUnits(fillSpec, priceTicks, quantitySteps, context);
            accountRepository.settleLiquidationFee(perpetualAccountType(fillSpec), userId, fillSpec.settleAsset(),
                    orderId, tradeId, symbol, marginMode, requestedFeeUnits, context, eventTime)
                    .ifPresent(settlement -> enqueueLiquidationFeeEvent(tradeId, orderId, userId, symbol,
                            marginMode, fillSpec.settleAsset(), settlement, eventTime, traceId));
        });
    }

    private long liquidationFeeUnits(ContractSpec fillSpec,
                                     long priceTicks,
                                     long quantitySteps,
                                     LiquidationFeeContext context) {
        if (context.feeRatePpm() <= 0) {
            return 0L;
        }
        long feeDeltaUnits = TradeFeeMath.feeDeltaUnits(fillSpec, priceTicks, quantitySteps, context.feeRatePpm());
        return feeDeltaUnits < 0 ? Math.absExact(feeDeltaUnits) : 0L;
    }

    private void enqueueLiquidationFeeEvent(long tradeId,
                                            long orderId,
                                            long userId,
                                            String symbol,
                                            MarginMode marginMode,
                                            String asset,
                                            LiquidationFeeSettlement settlement,
                                            Instant eventTime,
                                            String traceId) {
        if (outboxRepository == null || settlement.collectedFeeUnits() <= 0) {
            return;
        }
        outboxRepository.enqueueLiquidationFeeSettled(properties.getKafka().getLiquidationFeeEventsTopic(),
                tradeId, orderId, settlement.liquidationOrderId(), settlement.candidateId(), userId, symbol,
                marginMode, asset, settlement.collectedFeeUnits(), settlement.feeRatePpm(), eventTime, traceId);
    }

    private ContractSpec contractSpec(String symbol, long instrumentVersion) {
        return contractSpecCache.get(new ContractSpecKey(symbol, instrumentVersion),
                key -> accountRepository.contractSpec(key.symbol(), key.instrumentVersion()));
    }

    private OrderFeeSnapshot orderFeeSnapshot(long orderId, long userId, String symbol) {
        return orderFeeSnapshotCache.get(new OrderFeeSnapshotKey(orderId, userId, symbol),
                key -> accountRepository.orderFeeSnapshot(key.orderId(), key.userId(), key.symbol()));
    }

    private AccountType perpetualAccountType(ContractSpec spec) {
        return spec.contractType() == com.surprising.instrument.api.model.ContractType.INVERSE_PERPETUAL
                ? AccountType.COIN_PERPETUAL
                : AccountType.USDT_PERPETUAL;
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

    private String normalizeOptionalAsset(String asset) {
        return asset == null || asset.isBlank() ? null : normalizeAsset(asset);
    }

    private String normalizeOptionalReferenceType(String referenceType) {
        if (referenceType == null || referenceType.isBlank()) {
            return null;
        }
        String normalized = referenceType.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9_:-]{2,80}")) {
            throw new IllegalArgumentException("invalid referenceType: " + referenceType);
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

    private PositionSide normalizePositionSide(String positionSide) {
        if (positionSide == null || positionSide.isBlank()) {
            return PositionSide.NET;
        }
        PositionSide normalized;
        try {
            normalized = PositionSide.valueOf(positionSide.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid positionSide: " + positionSide, ex);
        }
        if (normalized.isHedgeSide()) {
            throw new IllegalArgumentException("hedge-mode positionSide is not supported; use NET");
        }
        return normalized;
    }

    private AccountType normalizeAccountType(AccountType accountType) {
        if (accountType == null) {
            throw new IllegalArgumentException("accountType is required");
        }
        return accountType;
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

    private String normalizeOptionalReferenceId(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            return null;
        }
        return normalizeReferenceId(referenceId);
    }

    private String normalizeOptionalAdjustmentKind(String adjustmentKind) {
        if (adjustmentKind == null || adjustmentKind.isBlank()) {
            return null;
        }
        String normalized = adjustmentKind.trim().toUpperCase();
        if (!"BASIC".equals(normalized) && !"PRODUCT".equals(normalized)) {
            throw new IllegalArgumentException("adjustmentKind must be BASIC or PRODUCT");
        }
        return normalized;
    }

    private long normalizeAdminUserId(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new IllegalArgumentException("adminUserId is required");
        }
        try {
            long value = Long.parseLong(adminUserId.trim());
            if (value <= 0) {
                throw new IllegalArgumentException("adminUserId must be positive");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("adminUserId must be numeric", ex);
        }
    }

    private String normalizeAdminUsername(String adminUsername) {
        if (adminUsername == null || adminUsername.isBlank()) {
            return null;
        }
        String normalized = adminUsername.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("adminUsername length must be <= 128");
        }
        return normalized;
    }

    private String normalizeReason(String reason, long amountUnits) {
        if (reason == null || reason.isBlank()) {
            return amountUnits > 0 ? "ADD_POSITION_MARGIN" : "REMOVE_POSITION_MARGIN";
        }
        String normalized = reason.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("reason length must be <= 128");
        }
        return normalized;
    }

    private void requireOptionalUserId(Long userId) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
    }

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        return limit;
    }

    private record ContractSpecKey(String symbol, long instrumentVersion) {
    }

    private record OrderFeeSnapshotKey(long orderId, long userId, String symbol) {
    }

    private static final class BoundedLocalCache<K, V> {

        private final int maxEntries;
        private final ConcurrentHashMap<K, V> values = new ConcurrentHashMap<>();

        private BoundedLocalCache(int maxEntries) {
            this.maxEntries = Math.max(0, maxEntries);
        }

        private V get(K key, Function<K, V> loader) {
            if (maxEntries == 0) {
                return loader.apply(key);
            }
            V cached = values.get(key);
            if (cached != null) {
                return cached;
            }
            V loaded = loader.apply(key);
            V existing = values.putIfAbsent(key, loaded);
            if (values.size() > maxEntries) {
                values.clear();
            }
            return existing == null ? loaded : existing;
        }
    }
}
