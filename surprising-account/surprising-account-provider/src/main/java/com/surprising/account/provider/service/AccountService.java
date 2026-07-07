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
import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.api.model.PositionModeUpdateRequest;
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
import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private final BoundedLocalCache<ContractSpecKey, InstrumentType> instrumentTypeCache;
    private final BoundedLocalCache<ContractSpecKey, SpotInstrumentSpec> spotInstrumentSpecCache;
    private final BoundedLocalCache<OrderFeeSnapshotKey, OrderFeeSnapshot> orderFeeSnapshotCache;
    private final BoundedLocalCache<OrderFeeSnapshotKey, Optional<LiquidationFeeContext>> liquidationFeeContextCache;

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
        this.instrumentTypeCache = new BoundedLocalCache<>(cacheProperties.getInstrumentTypeMaxEntries());
        this.spotInstrumentSpecCache = new BoundedLocalCache<>(cacheProperties.getSpotInstrumentSpecMaxEntries());
        this.orderFeeSnapshotCache = new BoundedLocalCache<>(cacheProperties.getOrderFeeSnapshotMaxEntries());
        this.liquidationFeeContextCache = new BoundedLocalCache<>(
                cacheProperties.getLiquidationFeeContextMaxEntries());
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
        AccountType accountType = normalizeScopedProductAccountType(request.accountType());
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
        AccountType accountType = normalizeScopedProductAccountType(request.accountType());
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
        AccountType normalizedType = normalizeScopedProductAccountType(accountType);
        String normalizedAsset = normalizeAsset(asset);
        return accountRepository.productBalance(userId, normalizedType, normalizedAsset)
                .orElse(new ProductBalanceResponse(userId, normalizedType, normalizedAsset, 0L, 0L, 0L,
                        Instant.EPOCH));
    }

    public ProductBalanceQueryResponse productBalances(long userId, AccountType accountType) {
        List<ProductBalanceResponse> rows = accountRepository.productBalances(userId,
                scopedProductAccountType(accountType));
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
        var page = accountRepository.productLedgerPage(userId, scopedProductAccountType(accountType),
                normalizeOptionalAsset(asset),
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
        var page = accountRepository.productTransferPage(userId, scopedProductAccountType(accountType),
                normalizeOptionalAsset(asset),
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
        requireScopedProductTransfer(source, target);
        return accountRepository.transferProductBalance(request.userId(), source, target, normalizeAsset(request.asset()),
                request.amountUnits(), normalizeReferenceId(request.referenceId()), request.reason());
    }

    public PositionModeResponse positionMode(long userId) {
        return positionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    public PositionModeResponse positionMode(ProductLine productLine, long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return accountRepository.positionMode(productLine, userId);
    }

    public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request) {
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return accountRepository.updatePositionMode(request.productLine(), request.userId(),
                request.positionMode(), Instant.now());
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
        PositionSide normalizedPositionSide = normalizePositionSide(positionSide);
        ProductLine productLine = currentProductLineFilter();
        Optional<PositionResponse> position = productLine == null
                ? accountRepository.position(userId, normalizedSymbol, normalizedMarginMode, normalizedPositionSide)
                : accountRepository.position(productLine, userId, normalizedSymbol, normalizedMarginMode,
                        normalizedPositionSide);
        return position
                .orElse(new PositionResponse(userId, normalizedSymbol, 0L, normalizedMarginMode,
                        normalizedPositionSide, 0L, 0L, 0L, Instant.EPOCH));
    }

    public PositionMarginResponse positionMargin(long userId, String symbol, String marginMode) {
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        ProductLine productLine = currentProductLineFilter();
        Optional<PositionMarginResponse> margin = productLine == null
                ? accountRepository.positionMargin(userId, normalizedSymbol, normalizedMarginMode, PositionSide.NET)
                : accountRepository.positionMargin(productLine, userId, normalizedSymbol, normalizedMarginMode,
                        PositionSide.NET);
        return margin
                .orElse(new PositionMarginResponse(userId, normalizedSymbol, "", normalizedMarginMode,
                        PositionSide.NET, 0L, Instant.EPOCH));
    }

    public PositionQueryResponse positions(long userId) {
        return positions(userId, null);
    }

    public PositionQueryResponse positions(long userId, String positionSide) {
        PositionSide normalizedPositionSide = positionSide == null || positionSide.isBlank()
                ? null
                : normalizePositionSide(positionSide);
        ProductLine productLine = currentProductLineFilter();
        List<PositionResponse> rows = productLine == null
                ? accountRepository.positions(userId, normalizedPositionSide)
                : accountRepository.positions(productLine, userId, normalizedPositionSide);
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
        ProductLine productLine = currentProductLineFilter();
        PositionMarginAdjustmentResponse response = productLine == null
                ? accountRepository.adjustIsolatedPositionMargin(
                        request.userId(), symbol, request.positionSide(), request.amountUnits(),
                        normalizeReferenceId(request.referenceId()), normalizeReason(request.reason(), request.amountUnits()),
                        properties.getPositionMargin().getMaxRiskSnapshotAge(),
                        properties.getPositionMargin().getRemovalBufferPpm())
                : accountRepository.adjustIsolatedPositionMargin(
                        productLine, request.userId(), symbol, request.positionSide(), request.amountUnits(),
                        normalizeReferenceId(request.referenceId()), normalizeReason(request.reason(), request.amountUnits()),
                        properties.getPositionMargin().getMaxRiskSnapshotAge(),
                        properties.getPositionMargin().getRemovalBufferPpm());
        if (outboxRepository != null) {
            Optional<PositionResponse> currentPosition = productLine == null
                    ? accountRepository.position(request.userId(), symbol, MarginMode.ISOLATED, request.positionSide())
                    : accountRepository.position(productLine, request.userId(), symbol, MarginMode.ISOLATED,
                            request.positionSide());
            PositionResponse current = currentPosition
                    .orElseThrow(() -> new IllegalStateException("isolated position missing after margin adjustment"));
            // Manual margin changes do not have a trade id; tradeId=0 tells downstream consumers this is a state trigger.
            outboxRepository.enqueuePositionUpdated(properties.getKafka().getPositionEventsTopic(),
                    0L, current, Instant.now(), TraceContext.currentOrCreate());
        }
        return response;
    }

    private ProductLine currentProductLineFilter() {
        AccountProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        return kafka != null && kafka.isProductTopicsEnabled() ? kafka.getProductLine() : null;
    }

    private AccountType currentProductAccountType() {
        ProductLine productLine = currentProductLineFilter();
        return productLine == null ? null : AccountType.valueOf(productLine.accountTypeCode());
    }

    private AccountType scopedProductAccountType(AccountType accountType) {
        AccountType currentAccountType = currentProductAccountType();
        if (currentAccountType == null) {
            return accountType;
        }
        if (accountType == null) {
            return currentAccountType;
        }
        requireCurrentProductAccountType(accountType, currentAccountType);
        return accountType;
    }

    private AccountType normalizeScopedProductAccountType(AccountType accountType) {
        AccountType normalizedType = normalizeAccountType(accountType);
        AccountType currentAccountType = currentProductAccountType();
        if (currentAccountType != null) {
            requireCurrentProductAccountType(normalizedType, currentAccountType);
        }
        return normalizedType;
    }

    private void requireScopedProductTransfer(AccountType source, AccountType target) {
        AccountType currentAccountType = currentProductAccountType();
        if (currentAccountType == null) {
            return;
        }
        if (!isFundingOrCurrentProduct(source, currentAccountType)
                || !isFundingOrCurrentProduct(target, currentAccountType)
                || (source != currentAccountType && target != currentAccountType)) {
            throw new IllegalArgumentException("transfer account types must include current product line account");
        }
    }

    private boolean isFundingOrCurrentProduct(AccountType accountType, AccountType currentAccountType) {
        return accountType == AccountType.FUNDING || accountType == currentAccountType;
    }

    private void requireCurrentProductAccountType(AccountType accountType, AccountType currentAccountType) {
        if (accountType != currentAccountType) {
            throw new IllegalArgumentException("accountType must match current product line account");
        }
    }

    @Transactional
    public void processTrade(MatchTradeEvent trade) {
        processTradeIfNew(trade);
    }

    @Transactional
    public int processDeliverySettlement(DeliverySettlementEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("delivery settlement event is required");
        }
        requireLifecycleClosed(event.status(), "delivery settlement");
        requireCashSettlement(event.settlementMethod());
        String symbol = normalizeSymbol(event.symbol());
        ContractSpec spec = contractSpec(symbol, event.version());
        requireMatchingDeliveryInstrument(event, spec);
        requireMatchingContractType(event.contractType(), spec.contractType(), "delivery settlement");
        if (!spec.contractType().isDelivery()) {
            throw new IllegalArgumentException("delivery settlement event must reference a delivery contract");
        }
        long settlementPriceTicks = accountRepository.latestMarkPriceTicks(symbol, event.version());
        return settleExpiringPositions(symbol, event.version(), settlementPriceTicks,
                event.eventTime(), "DELIVERY_SETTLEMENT", "DELIVERY_SETTLEMENT");
    }

    @Transactional
    public int processOptionExercise(OptionExerciseEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("option exercise event is required");
        }
        requireLifecycleClosed(event.status(), "option exercise");
        requireCashSettlement(event.settlementMethod());
        String symbol = normalizeSymbol(event.symbol());
        ContractSpec spec = contractSpec(symbol, event.version());
        requireMatchingOptionInstrument(event, spec);
        long settlementPriceTicks = optionIntrinsicPriceTicks(event, spec);
        return settleExpiringPositions(symbol, event.version(), settlementPriceTicks,
                event.eventTime(), "OPTION_EXERCISE", "OPTION_EXERCISE");
    }

    @Transactional
    public boolean processTradeIfNew(MatchTradeEvent trade) {
        if (!accountRepository.markTradeProcessing(trade.tradeId(), trade.symbol())) {
            return false;
        }
        String traceId = trade.traceId();
        InstrumentType takerInstrumentType = instrumentType(trade.symbol(), trade.takerInstrumentVersion());
        InstrumentType makerInstrumentType = trade.takerInstrumentVersion() == trade.makerInstrumentVersion()
                ? takerInstrumentType
                : instrumentType(trade.symbol(), trade.makerInstrumentVersion());
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
                trade.takerInstrumentVersion(), trade.takerSide(), trade.takerMarginMode(), trade.takerPositionSide(),
                trade.priceTicks(), trade.quantitySteps(),
                trade.takerOrderCompleted(), true, trade.eventTime(), traceId);
        applyTradeSide(trade.tradeId(), trade.makerOrderId(), trade.makerUserId(), trade.symbol(),
                trade.makerInstrumentVersion(), opposite(trade.takerSide()), trade.makerMarginMode(),
                trade.makerPositionSide(), trade.priceTicks(), trade.quantitySteps(),
                trade.makerOrderCompleted(), false, trade.eventTime(), traceId);
        return true;
    }

    private int settleExpiringPositions(String symbol,
                                        long instrumentVersion,
                                        long settlementPriceTicks,
                                        Instant eventTime,
                                        String referenceType,
                                        String reason) {
        ContractSpec spec = contractSpec(symbol, instrumentVersion);
        AccountType accountType = derivativeAccountType(spec);
        List<PositionResponse> positions = accountRepository.openPositionsForSettlement(symbol, instrumentVersion);
        int settled = 0;
        for (PositionResponse position : positions) {
            if (position.signedQuantitySteps() == 0L) {
                continue;
            }
            PositionState current = new PositionState(position.signedQuantitySteps(), position.instrumentVersion(),
                    position.entryPriceTicks(), position.realizedPnlUnits());
            PositionChange change = positionCalculator.closeAtSettlement(current, settlementPriceTicks, spec);
            String referenceId = lifecycleReferenceId(referenceType, symbol, instrumentVersion, position);
            long ledgerDeltaUnits = lifecycleLedgerDeltaUnits(referenceType, settlementPriceTicks, spec, position,
                    change);
            boolean applied = accountRepository.settleLifecyclePnl(accountType, position.userId(),
                    spec.settleAsset(), referenceType, referenceId, reason, symbol, position.marginMode(),
                    ledgerDeltaUnits, eventTime);
            if (!applied) {
                continue;
            }
            long closeSteps = Math.absExact(position.signedQuantitySteps());
            accountRepository.releasePositionMargin(position.userId(), symbol, position.marginMode(),
                    closeSteps, position.positionSide(), closeSteps, eventTime);
            PositionResponse updated = accountRepository.updatePosition(position.userId(), symbol,
                    position.marginMode(), position.positionSide(), change.next(), position.signedQuantitySteps(),
                    eventTime);
            if (outboxRepository != null) {
                outboxRepository.enqueuePositionUpdated(properties.getKafka().getPositionEventsTopic(),
                        0L, updated, eventTime, TraceContext.currentOrCreate());
            }
            settled++;
        }
        return settled;
    }

    private long optionIntrinsicPriceTicks(OptionExerciseEvent event, ContractSpec spec) {
        if (!spec.contractType().isOption()) {
            throw new IllegalArgumentException("option exercise event must reference an option contract");
        }
        String underlyingSymbol = normalizeSymbol(event.underlyingSymbol());
        long underlyingPriceUnits = accountRepository.latestMarkPriceUnits(underlyingSymbol);
        long strikePriceUnits = event.strikePriceUnits();
        if (strikePriceUnits <= 0) {
            throw new IllegalArgumentException("strikePriceUnits must be positive");
        }
        OptionType optionType = event.optionType();
        if (optionType == null) {
            throw new IllegalArgumentException("optionType is required");
        }
        long intrinsicUnits = switch (optionType) {
            case CALL -> Math.max(0L, Math.subtractExact(underlyingPriceUnits, strikePriceUnits));
            case PUT -> Math.max(0L, Math.subtractExact(strikePriceUnits, underlyingPriceUnits));
        };
        return Math.addExact(intrinsicUnits, spec.priceTickUnits() / 2L) / spec.priceTickUnits();
    }

    private void requireCashSettlement(ContractSettlementMethod settlementMethod) {
        if (settlementMethod != ContractSettlementMethod.CASH) {
            throw new IllegalArgumentException("only cash settlement is supported");
        }
    }

    private void requireLifecycleClosed(InstrumentStatus status, String eventName) {
        if (status != InstrumentStatus.CLOSED) {
            throw new IllegalArgumentException(eventName + " event must be CLOSED");
        }
    }

    private void requireMatchingOptionInstrument(OptionExerciseEvent event, ContractSpec spec) {
        InstrumentResponse instrument = event.instrument();
        if (instrument == null) {
            throw new IllegalArgumentException("option exercise event requires instrument snapshot");
        }
        String eventSymbol = normalizeSymbol(event.symbol());
        String instrumentSymbol = normalizeSymbol(instrument.symbol());
        if (!eventSymbol.equals(instrumentSymbol)) {
            throw new IllegalArgumentException("option exercise instrument symbol does not match event");
        }
        if (instrument.version() != event.version()) {
            throw new IllegalArgumentException("option exercise instrument version does not match event");
        }
        if (instrument.instrumentType() != InstrumentType.OPTION) {
            throw new IllegalArgumentException("option exercise instrument must be OPTION");
        }
        if (instrument.contractType() != spec.contractType()) {
            throw new IllegalArgumentException("option exercise instrument contract type does not match account spec");
        }
        if (instrument.contractType() == null || !instrument.contractType().isOption()) {
            throw new IllegalArgumentException("option exercise instrument must use an option contract type");
        }
        if (!Objects.equals(normalizeSymbol(event.underlyingSymbol()), normalizeSymbol(instrument.underlyingSymbol()))) {
            throw new IllegalArgumentException("option exercise underlyingSymbol does not match instrument");
        }
        if (!Objects.equals(event.strikePriceUnits(), instrument.strikePriceUnits())) {
            throw new IllegalArgumentException("option exercise strikePriceUnits does not match instrument");
        }
        if (event.optionType() != instrument.optionType()) {
            throw new IllegalArgumentException("option exercise optionType does not match instrument");
        }
        if (event.optionExerciseStyle() != instrument.optionExerciseStyle()) {
            throw new IllegalArgumentException("option exercise optionExerciseStyle does not match instrument");
        }
        if (event.settlementMethod() != instrument.settlementMethod()) {
            throw new IllegalArgumentException("option exercise settlementMethod does not match instrument");
        }
        if (event.status() != instrument.status()) {
            throw new IllegalArgumentException("option exercise status does not match instrument");
        }
    }

    private void requireMatchingDeliveryInstrument(DeliverySettlementEvent event, ContractSpec spec) {
        InstrumentResponse instrument = event.instrument();
        if (instrument == null) {
            throw new IllegalArgumentException("delivery settlement event requires instrument snapshot");
        }
        String eventSymbol = normalizeSymbol(event.symbol());
        String instrumentSymbol = normalizeSymbol(instrument.symbol());
        if (!eventSymbol.equals(instrumentSymbol)) {
            throw new IllegalArgumentException("delivery settlement instrument symbol does not match event");
        }
        if (instrument.version() != event.version()) {
            throw new IllegalArgumentException("delivery settlement instrument version does not match event");
        }
        if (instrument.instrumentType() != InstrumentType.DELIVERY) {
            throw new IllegalArgumentException("delivery settlement instrument must be DELIVERY");
        }
        if (instrument.contractType() != spec.contractType()) {
            throw new IllegalArgumentException(
                    "delivery settlement instrument contract type does not match account spec");
        }
        if (event.contractType() != null && event.contractType() != instrument.contractType()) {
            throw new IllegalArgumentException("delivery settlement contract type does not match instrument");
        }
        if (instrument.contractType() == null || !instrument.contractType().isDelivery()) {
            throw new IllegalArgumentException("delivery settlement instrument must use a delivery contract type");
        }
        if (event.settlementMethod() != instrument.settlementMethod()) {
            throw new IllegalArgumentException("delivery settlement settlementMethod does not match instrument");
        }
        if (event.status() != instrument.status()) {
            throw new IllegalArgumentException("delivery settlement status does not match instrument");
        }
    }

    private void requireMatchingContractType(ContractType eventContractType,
                                             ContractType instrumentContractType,
                                             String eventName) {
        if (eventContractType != null && eventContractType != instrumentContractType) {
            throw new IllegalArgumentException(eventName + " contract type does not match instrument");
        }
    }

    private long lifecycleLedgerDeltaUnits(String referenceType,
                                           long settlementPriceTicks,
                                           ContractSpec spec,
                                           PositionResponse position,
                                           PositionChange change) {
        if (spec.contractType().isOption() && "OPTION_EXERCISE".equals(referenceType)) {
            return MarginTransferMath.optionExercisePayoffUnits(spec, settlementPriceTicks,
                    position.signedQuantitySteps());
        }
        return change.realizedPnlDeltaUnits();
    }

    private String lifecycleReferenceId(String referenceType,
                                        String symbol,
                                        long instrumentVersion,
                                        PositionResponse position) {
        return referenceType + ":" + symbol + ":" + instrumentVersion + ":" + position.userId()
                + ":" + position.marginMode().name() + ":" + position.positionSide().name();
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
        SpotInstrumentSpec spec = spotInstrumentSpec(symbol, instrumentVersion);
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
                                            PositionSide positionSide,
                                            long priceTicks,
                                            long quantitySteps,
                                            boolean orderCompleted,
                                            boolean taker,
                                            Instant eventTime,
                                            String traceId) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        ContractSpec fillSpec = contractSpec(symbol, fillInstrumentVersion);
        ProductLine productLine = ProductLine.requireContractTypeCode(fillSpec.contractType().name());
        PositionState current = accountRepository.lockPosition(productLine, userId, symbol, normalizedMarginMode,
                normalizedPositionSide);
        ContractSpec positionSpec = current.signedQuantitySteps() == 0
                ? fillSpec
                : contractSpec(symbol, current.instrumentVersion());
        long closeSteps = MarginTransferMath.closeSteps(current.signedQuantitySteps(), side, quantitySteps);
        long openSteps = Math.subtractExact(quantitySteps, closeSteps);
        PositionChange change = positionCalculator.apply(current, side, priceTicks, quantitySteps,
                positionSpec, fillSpec);
        if (fillSpec.contractType().isOption()) {
            accountRepository.settleOptionPremium(derivativeAccountType(fillSpec), side, userId,
                    fillSpec.settleAsset(), orderId, tradeId, symbol, normalizedMarginMode,
                    MarginTransferMath.optionPremiumUnits(fillSpec, priceTicks, quantitySteps), orderCompleted,
                    eventTime);
        }
        if (closeSteps > 0 && !positionSpec.contractType().isOption()) {
            accountRepository.settleRealizedPnl(derivativeAccountType(positionSpec), userId,
                    positionSpec.settleAsset(), orderId, tradeId, symbol, normalizedMarginMode,
                    change.realizedPnlDeltaUnits(), eventTime);
        }
        if (openSteps > 0) {
            if (!fillSpec.contractType().isOption() || side == OrderSide.SELL) {
                long actualMarginUnits = MarginTransferMath.openingInitialMarginUnits(fillSpec, priceTicks, openSteps);
                accountRepository.consumeOrderMargin(productLine, orderId, userId, symbol, normalizedMarginMode, openSteps,
                        actualMarginUnits, orderCompleted, eventTime);
            }
        }
        OrderFeeSnapshot feeSnapshot = orderFeeSnapshot(orderId, userId, symbol);
        long feeRatePpm = taker ? feeSnapshot.takerFeeRatePpm() : feeSnapshot.makerFeeRatePpm();
        long feeDeltaUnits = TradeFeeMath.feeDeltaUnits(fillSpec, priceTicks, quantitySteps, feeRatePpm);
        accountRepository.settleTradeFee(derivativeAccountType(fillSpec), userId, fillSpec.settleAsset(),
                orderId, tradeId, feeDeltaUnits, taker ? "TAKER_FEE" : "MAKER_FEE", feeRatePpm, symbol,
                normalizedMarginMode, eventTime);
        if (closeSteps > 0) {
            settleLiquidationFeeIfNeeded(tradeId, orderId, userId, symbol, normalizedMarginMode, fillSpec,
                    priceTicks, quantitySteps, eventTime, traceId);
        }
        if (closeSteps > 0) {
            accountRepository.releasePositionMargin(productLine, userId, symbol, normalizedMarginMode, closeSteps,
                    normalizedPositionSide, Math.absExact(current.signedQuantitySteps()), eventTime);
            accountRepository.releaseOrderMargin(orderId, userId, symbol, closeSteps,
                    orderCompleted && openSteps == 0, eventTime);
        }
        PositionResponse updated = accountRepository.updatePosition(productLine, userId, symbol, normalizedMarginMode,
                normalizedPositionSide,
                change.next(), current.signedQuantitySteps(), eventTime);
        if (closeSteps > 0 && reduceOnlyOrderPruner != null) {
            reduceOnlyOrderPruner.prune(userId, symbol, normalizedPositionSide, change.next(), eventTime, traceId);
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
        liquidationFeeContext(orderId, userId, symbol).ifPresent(context -> {
            long requestedFeeUnits = liquidationFeeUnits(fillSpec, priceTicks, quantitySteps, context);
            AccountType accountType = derivativeAccountType(fillSpec);
            accountRepository.settleLiquidationFee(accountType, userId, fillSpec.settleAsset(),
                    orderId, tradeId, symbol, marginMode, requestedFeeUnits, context, eventTime)
                    .ifPresent(settlement -> enqueueLiquidationFeeEvent(tradeId, orderId, userId, symbol,
                            marginMode, accountType, fillSpec.settleAsset(), settlement, eventTime, traceId));
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
                                            AccountType accountType,
                                            String asset,
                                            LiquidationFeeSettlement settlement,
                                            Instant eventTime,
                                            String traceId) {
        if (outboxRepository == null || settlement.collectedFeeUnits() <= 0) {
            return;
        }
        outboxRepository.enqueueLiquidationFeeSettled(properties.getKafka().getLiquidationFeeEventsTopic(),
                tradeId, orderId, settlement.liquidationOrderId(), settlement.candidateId(), userId, symbol,
                marginMode, accountType.name(), asset, settlement.collectedFeeUnits(), settlement.feeRatePpm(),
                eventTime, traceId);
    }

    private ContractSpec contractSpec(String symbol, long instrumentVersion) {
        return contractSpecCache.get(new ContractSpecKey(symbol, instrumentVersion),
                key -> accountRepository.contractSpec(key.symbol(), key.instrumentVersion()));
    }

    private InstrumentType instrumentType(String symbol, long instrumentVersion) {
        return instrumentTypeCache.get(new ContractSpecKey(symbol, instrumentVersion),
                key -> accountRepository.instrumentType(key.symbol(), key.instrumentVersion()));
    }

    private SpotInstrumentSpec spotInstrumentSpec(String symbol, long instrumentVersion) {
        return spotInstrumentSpecCache.get(new ContractSpecKey(symbol, instrumentVersion),
                key -> accountRepository.spotInstrumentSpec(key.symbol(), key.instrumentVersion()));
    }

    private OrderFeeSnapshot orderFeeSnapshot(long orderId, long userId, String symbol) {
        return orderFeeSnapshotCache.get(new OrderFeeSnapshotKey(orderId, userId, symbol),
                key -> accountRepository.orderFeeSnapshot(key.orderId(), key.userId(), key.symbol()));
    }

    private Optional<LiquidationFeeContext> liquidationFeeContext(long orderId, long userId, String symbol) {
        return liquidationFeeContextCache.get(new OrderFeeSnapshotKey(orderId, userId, symbol),
                key -> accountRepository.liquidationFeeContext(key.orderId(), key.userId(), key.symbol()));
    }

    private AccountType derivativeAccountType(ContractSpec spec) {
        ContractType contractType = spec.contractType();
        if (contractType == ContractType.SPOT) {
            throw new IllegalArgumentException("unsupported derivative settlement contract type: " + contractType);
        }
        return AccountType.valueOf(contractType.productLine().accountTypeCode());
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
