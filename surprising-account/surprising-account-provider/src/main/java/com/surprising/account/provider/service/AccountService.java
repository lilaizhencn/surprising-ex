package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ExpiringPositionSettlementAccountCommand;
import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.account.api.model.TradeSideSettlementCommand;
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
import com.surprising.account.provider.model.PositionChange;
import com.surprising.account.provider.model.PositionSettlementState;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.account.provider.repository.AccountInstrumentRepository;
import com.surprising.account.provider.repository.AdminBalanceAdjustmentRepository;
import com.surprising.account.provider.repository.PositionModeRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.account.provider.repository.TradeSettlementSideRepository;
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
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Duration;
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

    private final AccountSettlementService accountSettlementService;
    private final AccountQueryService accountQueryService;
    private final AccountBalanceCommandService accountBalanceCommandService;
    private final AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository;
    private final PositionModeRepository positionModeRepository;
    private final TradeSettlementSideRepository tradeSettlementSideRepository;
    private final PositionModeCommandService positionModeCommandService;
    private final PositionRepository positionRepository;
    private final PositionQueryService positionQueryService;
    private final AccountInstrumentRepository accountInstrumentRepository;
    private final SpotTradeSettlementService spotTradeSettlementService;
    private final PositionCalculator positionCalculator;
    private final AccountProperties properties;
    private final AccountOutboxService outboxService;
    private final RedisPositionCache positionCache;
    private final PositionCacheAfterCommitSynchronizer positionCacheAfterCommitSynchronizer;
    private final BoundedLocalCache<ContractSpecKey, ContractSpec> contractSpecCache;
    private final BoundedLocalCache<ContractSpecKey, InstrumentType> instrumentTypeCache;
    private final BoundedLocalCache<ContractSpecKey, SpotInstrumentSpec> spotInstrumentSpecCache;
    private final BoundedLocalCache<LiquidationFeeContextKey, Optional<LiquidationFeeContext>>
            liquidationFeeContextCache;

    public AccountService(AccountSettlementService accountSettlementService, PositionCalculator positionCalculator) {
        this(accountSettlementService, positionCalculator, new AccountProperties(), null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public AccountService(AccountSettlementService accountSettlementService,
                           PositionCalculator positionCalculator,
                           AccountProperties properties,
                           AccountOutboxService outboxService) {
        this(accountSettlementService, positionCalculator, properties, outboxService, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public AccountService(AccountSettlementService accountSettlementService,
                           PositionCalculator positionCalculator,
                           AccountProperties properties,
                           AccountOutboxService outboxService,
                           RedisPositionCache positionCache) {
        this(accountSettlementService, positionCalculator, properties, outboxService, positionCache, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    @Autowired
    public AccountService(AccountSettlementService accountSettlementService,
                           PositionCalculator positionCalculator,
                           AccountProperties properties,
                           AccountOutboxService outboxService,
                           RedisPositionCache positionCache,
                           PositionCacheAfterCommitSynchronizer positionCacheAfterCommitSynchronizer,
                           AccountQueryService accountQueryService,
                           AccountBalanceCommandService accountBalanceCommandService,
                           AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository,
                           PositionModeRepository positionModeRepository,
                           TradeSettlementSideRepository tradeSettlementSideRepository,
                           PositionModeCommandService positionModeCommandService,
                           PositionRepository positionRepository,
                           PositionQueryService positionQueryService,
                           AccountInstrumentRepository accountInstrumentRepository,
                           SpotTradeSettlementService spotTradeSettlementService) {
        this.accountSettlementService = accountSettlementService;
        this.accountQueryService = accountQueryService;
        this.accountBalanceCommandService = accountBalanceCommandService;
        this.adminBalanceAdjustmentRepository = adminBalanceAdjustmentRepository;
        this.positionModeRepository = positionModeRepository;
        this.tradeSettlementSideRepository = tradeSettlementSideRepository;
        this.positionModeCommandService = positionModeCommandService;
        this.positionRepository = positionRepository;
        this.positionQueryService = positionQueryService;
        this.accountInstrumentRepository = accountInstrumentRepository;
        this.spotTradeSettlementService = spotTradeSettlementService;
        this.positionCalculator = positionCalculator;
        this.properties = properties;
        this.outboxService = outboxService;
        this.positionCache = positionCache;
        this.positionCacheAfterCommitSynchronizer = positionCacheAfterCommitSynchronizer;
        AccountProperties.Cache cacheProperties = properties.getCache() == null
                ? new AccountProperties.Cache()
                : properties.getCache();
        this.contractSpecCache = new BoundedLocalCache<>(cacheProperties.getContractSpecMaxEntries());
        this.instrumentTypeCache = new BoundedLocalCache<>(cacheProperties.getInstrumentTypeMaxEntries());
        this.spotInstrumentSpecCache = new BoundedLocalCache<>(cacheProperties.getSpotInstrumentSpecMaxEntries());
        this.liquidationFeeContextCache = new BoundedLocalCache<>(
                cacheProperties.getLiquidationFeeContextMaxEntries());
    }

    @Transactional
    public BalanceResponse adjustBalance(BalanceAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        return adjustBalance(request.userId(), normalizeAsset(request.asset()), request.amountUnits(),
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
        BalanceResponse response = adjustBalance(request.userId(), normalizedAsset, request.amountUnits(),
                normalizedReferenceId, request.reason());
        recordAdminBalanceAdjustment("BASIC", normalizedAdminUserId, normalizeAdminUsername(adminUsername),
                request.userId(), null, normalizedAsset, request.amountUnits(), response.availableUnits(),
                normalizedReferenceId, request.reason());
        return response;
    }

    public BalanceResponse balance(long userId, String asset) {
        String normalizedAsset = normalizeAsset(asset);
        return (accountQueryService == null
                ? accountSettlementService.balance(userId, normalizedAsset)
                : accountQueryService.balance(userId, normalizedAsset))
                .orElse(new BalanceResponse(userId, normalizedAsset, 0L, 0L, 0L, Instant.EPOCH));
    }

    public BalanceQueryResponse balances(long userId) {
        List<BalanceResponse> rows = accountQueryService == null
                ? accountSettlementService.balances(userId)
                : accountQueryService.balances(userId);
        return new BalanceQueryResponse(rows.size(), rows);
    }

    @Transactional
    public ProductBalanceResponse adjustProductBalance(ProductBalanceAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        AccountType accountType = normalizeScopedProductAccountType(request.accountType());
        return adjustProductBalance(request.userId(), accountType, normalizeAsset(request.asset()),
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
        ProductBalanceResponse response = adjustProductBalance(request.userId(), accountType, normalizedAsset,
                request.amountUnits(), normalizedReferenceId, request.reason());
        recordAdminBalanceAdjustment("PRODUCT", normalizedAdminUserId, normalizeAdminUsername(adminUsername),
                request.userId(), accountType, normalizedAsset, request.amountUnits(), response.availableUnits(),
                normalizedReferenceId, request.reason());
        return response;
    }

    public ProductBalanceResponse productBalance(long userId, AccountType accountType, String asset) {
        AccountType normalizedType = normalizeScopedProductAccountType(accountType);
        String normalizedAsset = normalizeAsset(asset);
        return (accountQueryService == null
                ? accountSettlementService.productBalance(userId, normalizedType, normalizedAsset)
                : accountQueryService.productBalance(userId, normalizedType, normalizedAsset))
                .orElse(new ProductBalanceResponse(userId, normalizedType, normalizedAsset, 0L, 0L, 0L,
                        Instant.EPOCH));
    }

    public ProductBalanceQueryResponse productBalances(long userId, AccountType accountType) {
        AccountType scopedAccountType = scopedProductAccountType(accountType);
        List<ProductBalanceResponse> rows = accountQueryService == null
                ? accountSettlementService.productBalances(userId, scopedAccountType)
                : accountQueryService.productBalances(userId, scopedAccountType);
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
        var page = accountQueryService == null
                ? accountSettlementService.accountLedgerPage(userId, normalizeOptionalAsset(asset),
                        normalizeOptionalReferenceType(referenceType), safeLimit, cursor, sort)
                : accountQueryService.accountLedgerPage(userId, normalizeOptionalAsset(asset),
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
        var page = accountQueryService == null
                ? accountSettlementService.productLedgerPage(userId, scopedProductAccountType(accountType),
                        normalizeOptionalAsset(asset), normalizeOptionalReferenceType(referenceType),
                        safeLimit, cursor, sort)
                : accountQueryService.productLedgerPage(userId, scopedProductAccountType(accountType),
                        normalizeOptionalAsset(asset), normalizeOptionalReferenceType(referenceType),
                        safeLimit, cursor, sort);
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
        var page = accountQueryService == null
                ? accountSettlementService.productTransferPage(userId, scopedProductAccountType(accountType),
                        normalizeOptionalAsset(asset), safeLimit, cursor, sort)
                : accountQueryService.productTransferPage(userId, scopedProductAccountType(accountType),
                        normalizeOptionalAsset(asset), safeLimit, cursor, sort);
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
        var page = accountQueryService == null
                ? accountSettlementService.adminBalanceAdjustmentPage(adminUserId, userId,
                        normalizeOptionalAdjustmentKind(adjustmentKind), accountType, normalizeOptionalAsset(asset),
                        normalizeOptionalReferenceId(referenceId), safeLimit, cursor, sort)
                : accountQueryService.adminBalanceAdjustmentPage(adminUserId, userId,
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
        if (accountBalanceCommandService == null) {
            return accountSettlementService.transferProductBalance(request.userId(), source, target,
                    normalizeAsset(request.asset()), request.amountUnits(),
                    normalizeReferenceId(request.referenceId()), request.reason());
        }
        return accountBalanceCommandService.transferProductBalance(request.userId(), source, target,
                normalizeAsset(request.asset()), request.amountUnits(),
                normalizeReferenceId(request.referenceId()), request.reason());
    }

    public PositionModeResponse positionMode(long userId) {
        return positionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    public PositionModeResponse positionMode(ProductLine productLine, long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (positionModeRepository == null) {
            return accountSettlementService.positionMode(productLine, userId);
        }
        ProductLine resolvedProductLine = productLine == null
                ? ProductLine.LINEAR_PERPETUAL
                : productLine;
        return positionModeRepository.find(resolvedProductLine, userId)
                .map(row -> new PositionModeResponse(
                        resolvedProductLine, userId, row.positionMode(), row.updatedAt()))
                .orElse(new PositionModeResponse(
                        resolvedProductLine, userId, PositionMode.ONE_WAY, Instant.EPOCH));
    }

    public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request) {
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (positionModeCommandService != null) {
            return positionModeCommandService.update(
                    request.productLine(), request.userId(), request.positionMode(), Instant.now());
        }
        return accountSettlementService.updatePositionMode(request.productLine(), request.userId(),
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
        if (positionCache != null) {
            return positionCache.position(positionCacheProductLine(), userId, normalizedSymbol,
                    normalizedMarginMode, normalizedPositionSide);
        }
        ProductLine productLine = currentProductLineFilter();
        Optional<PositionResponse> position = findPosition(
                productLine, userId, normalizedSymbol, normalizedMarginMode, normalizedPositionSide);
        return position
                .orElse(new PositionResponse(userId, normalizedSymbol, 0L, normalizedMarginMode,
                        normalizedPositionSide, 0L, 0L, 0L, Instant.EPOCH));
    }

    public PositionMarginResponse positionMargin(long userId, String symbol, String marginMode) {
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        if (positionCache != null) {
            return positionCache.positionMargin(positionCacheProductLine(), userId, normalizedSymbol,
                    normalizedMarginMode, PositionSide.NET);
        }
        ProductLine productLine = currentProductLineFilter();
        Optional<PositionMarginResponse> margin;
        if (positionQueryService == null) {
            margin = productLine == null
                    ? accountSettlementService.positionMargin(
                            userId, normalizedSymbol, normalizedMarginMode, PositionSide.NET)
                    : accountSettlementService.positionMargin(
                            productLine, userId, normalizedSymbol, normalizedMarginMode, PositionSide.NET);
        } else {
            margin = productLine == null
                    ? positionQueryService.positionMargin(
                            userId, normalizedSymbol, normalizedMarginMode, PositionSide.NET)
                    : positionQueryService.positionMargin(
                            productLine, userId, normalizedSymbol, normalizedMarginMode, PositionSide.NET);
        }
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
        if (positionCache != null) {
            List<PositionResponse> rows = positionCache.positions(
                    positionCacheProductLine(), userId, normalizedPositionSide);
            return new PositionQueryResponse(rows.size(), rows);
        }
        ProductLine productLine = currentProductLineFilter();
        List<PositionResponse> rows = findPositions(productLine, userId, normalizedPositionSide);
        return new PositionQueryResponse(rows.size(), rows);
    }

    public PositionResponse adminPosition(long userId, String symbol, String marginMode, String positionSide) {
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        PositionSide normalizedPositionSide = normalizePositionSide(positionSide);
        ProductLine productLine = positionCacheProductLine();
        return findPosition(productLine, userId, normalizedSymbol, normalizedMarginMode, normalizedPositionSide)
                .orElse(new PositionResponse(userId, normalizedSymbol, 0L, normalizedMarginMode,
                        normalizedPositionSide, 0L, 0L, 0L, Instant.EPOCH));
    }

    public PositionQueryResponse adminPositions(long userId, String positionSide) {
        PositionSide normalizedPositionSide = positionSide == null || positionSide.isBlank()
                ? null
                : normalizePositionSide(positionSide);
        List<PositionResponse> rows = findPositions(
                positionCacheProductLine(), userId, normalizedPositionSide);
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
                ? accountSettlementService.adjustIsolatedPositionMargin(
                        request.userId(), symbol, request.positionSide(), request.amountUnits(),
                        normalizeReferenceId(request.referenceId()), normalizeReason(request.reason(), request.amountUnits()),
                        properties.getPositionMargin().getMaxRiskSnapshotAge(),
                        properties.getPositionMargin().getRemovalBufferPpm())
                : accountSettlementService.adjustIsolatedPositionMargin(
                        productLine, request.userId(), symbol, request.positionSide(), request.amountUnits(),
                        normalizeReferenceId(request.referenceId()), normalizeReason(request.reason(), request.amountUnits()),
                        properties.getPositionMargin().getMaxRiskSnapshotAge(),
                        properties.getPositionMargin().getRemovalBufferPpm());
        if (outboxService != null) {
            Optional<PositionResponse> currentPosition = findPosition(
                    productLine, request.userId(), symbol, MarginMode.ISOLATED, request.positionSide());
            PositionResponse current = currentPosition
                    .orElseThrow(() -> new IllegalStateException("isolated position missing after margin adjustment"));
            // 手工调整保证金没有成交编号，tradeId=0 用于告知下游这是一次状态触发。
            var event = outboxService.enqueuePositionUpdated(properties.getKafka().getPositionEventsTopic(),
                    0L, current, Instant.now(), TraceContext.currentOrCreate());
            schedulePositionCacheSync(event.cacheEvent());
        } else {
            schedulePositionCacheSync(productLine == null ? positionCacheProductLine() : productLine,
                    request.userId(), symbol, MarginMode.ISOLATED, request.positionSide());
        }
        return response;
    }

    private ProductLine currentProductLineFilter() {
        AccountProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        return kafka != null && kafka.isProductTopicsEnabled() ? kafka.getProductLine() : null;
    }

    private ProductLine positionCacheProductLine() {
        AccountProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        return kafka == null || kafka.getProductLine() == null
                ? ProductLine.LINEAR_PERPETUAL
                : kafka.getProductLine();
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

    public List<UserExpiringSettlementPlan> planDeliverySettlement(DeliverySettlementEvent event) {
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
        Instant settlementTime = settlementTime(event.deliveryTime(), event.eventTime());
        Duration priceWindow = settlementPriceWindow();
        ProductLine productLine = spec.contractType().productLine();
        return lockOpenPositionStatesForSettlement(productLine, symbol).stream()
                .filter(position -> position.signedQuantitySteps() != 0L)
                .map(position -> new UserExpiringSettlementPlan(
                        productLine,
                        position.userId(),
                        new ExpiringPositionSettlementAccountCommand(
                                symbol, position.instrumentVersion(), position.marginMode(), position.positionSide(),
                                accountSettlementService.settlementMarkPriceTicks(
                                        symbol, position.instrumentVersion(), settlementTime, priceWindow),
                                "DELIVERY_SETTLEMENT", "DELIVERY_SETTLEMENT", settlementTime)))
                .toList();
    }

    public List<UserExpiringSettlementPlan> planOptionExercise(OptionExerciseEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("option exercise event is required");
        }
        requireLifecycleClosed(event.status(), "option exercise");
        requireCashSettlement(event.settlementMethod());
        String symbol = normalizeSymbol(event.symbol());
        ContractSpec spec = contractSpec(symbol, event.version());
        requireMatchingOptionInstrument(event, spec);
        String underlyingSymbol = normalizeSymbol(event.underlyingSymbol());
        Instant settlementTime = settlementTime(event.deliveryTime(), event.eventTime());
        long underlyingPriceUnits = accountSettlementService.settlementMarkPriceUnits(
                underlyingSymbol, settlementTime, settlementPriceWindow());
        ProductLine productLine = spec.contractType().productLine();
        return lockOpenPositionStatesForSettlement(productLine, symbol).stream()
                .filter(position -> position.signedQuantitySteps() != 0L)
                .map(position -> {
                    ContractSpec positionSpec = contractSpec(symbol, position.instrumentVersion());
                    return new UserExpiringSettlementPlan(
                            productLine,
                            position.userId(),
                            new ExpiringPositionSettlementAccountCommand(
                                    symbol, position.instrumentVersion(), position.marginMode(),
                                    position.positionSide(),
                                    optionIntrinsicPriceTicks(event, positionSpec, underlyingPriceUnits),
                                    "OPTION_EXERCISE", "OPTION_EXERCISE", settlementTime));
                })
                .toList();
    }

    public Optional<PositionResponse> processExpiringPosition(
            ProductLine productLine,
            long userId,
            String commandId,
            ExpiringPositionSettlementAccountCommand command) {
        PositionSettlementState position = lockOpenPositionStatesForSettlement(
                productLine, command.symbol()).stream()
                .filter(candidate -> candidate.userId() == userId
                        && candidate.instrumentVersion() == command.instrumentVersion()
                        && candidate.marginMode() == command.marginMode()
                        && candidate.positionSide() == command.positionSide())
                .findFirst()
                .orElse(null);
        if (position == null || position.signedQuantitySteps() == 0L) {
            return Optional.empty();
        }
        ContractSpec spec = contractSpec(command.symbol(), position.instrumentVersion());
        if (spec.contractType().productLine() != productLine) {
            throw new IllegalStateException("expiring position product line mismatch");
        }
        PositionChange change = positionCalculator.closeAtSettlement(position.state(),
                command.settlementPriceTicks(), spec);
        long ledgerDeltaUnits = lifecycleLedgerDeltaUnits(command.referenceType(),
                command.settlementPriceTicks(), spec, position, change);
        boolean applied = accountSettlementService.settleLifecyclePnl(
                derivativeAccountType(spec), userId, spec.settleAsset(), command.referenceType(), commandId,
                command.reason(), command.symbol(), position.marginMode(), ledgerDeltaUnits, command.eventTime());
        if (!applied) {
            return findPosition(productLine, userId, command.symbol(),
                    position.marginMode(), position.positionSide());
        }
        long closeSteps = Math.absExact(position.signedQuantitySteps());
        accountSettlementService.releasePositionMargin(productLine, userId, command.symbol(), position.marginMode(),
                closeSteps, position.positionSide(), closeSteps, command.eventTime());
        PositionResponse updated = accountSettlementService.updatePosition(productLine, userId, command.symbol(),
                position.marginMode(), position.positionSide(), change.next(), position.signedQuantitySteps(),
                command.eventTime());
        var event = outboxService.enqueuePositionUpdated(properties.getKafka().getPositionEventsTopic(),
                0L, updated, command.eventTime(), TraceContext.currentOrCreate());
        schedulePositionCacheSync(event.cacheEvent());
        return Optional.of(updated);
    }

    public record UserExpiringSettlementPlan(
            ProductLine productLine,
            long userId,
            ExpiringPositionSettlementAccountCommand command) {
    }

    /**
     * Applies exactly one participant of a match. Idempotency is owned by account_commands and the
     * participant completion is stored as an immutable account_trade_settlement_sides row, so taker
     * and maker account partitions never update the same database row.
     */
    public void processTradeSide(ProductLine commandProductLine,
                                 String commandId,
                                 TradeSideSettlementCommand sideCommand) {
        MatchTradeEvent trade = sideCommand.trade();
        TradeParticipantRole role = sideCommand.participantRole();
        InstrumentType takerInstrumentType = instrumentType(trade.symbol(), trade.takerInstrumentVersion());
        InstrumentType makerInstrumentType = trade.takerInstrumentVersion() == trade.makerInstrumentVersion()
                ? takerInstrumentType
                : instrumentType(trade.symbol(), trade.makerInstrumentVersion());
        if (takerInstrumentType != makerInstrumentType) {
            throw new IllegalStateException("matched orders use different instrument types for " + trade.symbol());
        }
        ProductLine actualProductLine = takerInstrumentType == InstrumentType.SPOT
                ? ProductLine.SPOT
                : contractSpec(trade.symbol(), trade.takerInstrumentVersion()).contractType().productLine();
        if (actualProductLine != commandProductLine) {
            throw new IllegalArgumentException("trade product line does not match account command: expected="
                    + actualProductLine + " actual=" + commandProductLine);
        }
        long expectedUserId = role == TradeParticipantRole.TAKER ? trade.takerUserId() : trade.makerUserId();
        if (expectedUserId != sideCommand.userId()) {
            throw new IllegalArgumentException("trade side user does not match participant role");
        }
        Instant effectiveAt = trade.eventTime() == null ? Instant.now() : trade.eventTime();
        AccountSettlementService.OrderMarginApplication marginApplication = AccountSettlementService.OrderMarginApplication.NONE;
        if (takerInstrumentType == InstrumentType.SPOT) {
            if (role == TradeParticipantRole.TAKER) {
                applySpotTradeSide(trade.tradeId(), trade.takerOrderId(), trade.takerUserId(), trade.symbol(),
                        trade.takerInstrumentVersion(), trade.takerSide(), trade.priceTicks(), trade.quantitySteps(),
                        trade.takerOrderCompleted(), trade.takerFeeRatePpm(), "TAKER_FEE", effectiveAt);
            } else {
                applySpotTradeSide(trade.tradeId(), trade.makerOrderId(), trade.makerUserId(), trade.symbol(),
                        trade.makerInstrumentVersion(), opposite(trade.takerSide()), trade.priceTicks(),
                        trade.quantitySteps(), trade.makerOrderCompleted(), trade.makerFeeRatePpm(), "MAKER_FEE",
                        effectiveAt);
            }
        } else if (role == TradeParticipantRole.TAKER) {
            marginApplication = applyTradeSide(trade.tradeId(), trade.takerOrderId(), trade.takerUserId(), trade.symbol(),
                    trade.takerInstrumentVersion(), trade.takerSide(), trade.takerMarginMode(),
                    trade.takerPositionSide(), trade.priceTicks(), trade.quantitySteps(),
                    sideCommand.orderQuantitySteps(), sideCommand.reduceOnly(),
                    sideCommand.reservationAccountType(), sideCommand.reservationAsset(), sideCommand.reservedUnits(),
                    trade.takerOrderCompleted(), trade.takerFeeRatePpm(), "TAKER_FEE", effectiveAt, trade.traceId());
        } else {
            marginApplication = applyTradeSide(trade.tradeId(), trade.makerOrderId(), trade.makerUserId(), trade.symbol(),
                    trade.makerInstrumentVersion(), opposite(trade.takerSide()), trade.makerMarginMode(),
                    trade.makerPositionSide(), trade.priceTicks(), trade.quantitySteps(),
                    sideCommand.orderQuantitySteps(), sideCommand.reduceOnly(),
                    sideCommand.reservationAccountType(), sideCommand.reservationAsset(), sideCommand.reservedUnits(),
                    trade.makerOrderCompleted(), trade.makerFeeRatePpm(), "MAKER_FEE", effectiveAt, trade.traceId());
        }
        if (tradeSettlementSideRepository == null) {
            accountSettlementService.completeTradeSide(actualProductLine, trade, role, commandId,
                    marginApplication.consumedUnits(), marginApplication.releasedUnits(), Instant.now());
        } else {
            tradeSettlementSideRepository.complete(actualProductLine, trade, role, commandId,
                    marginApplication.consumedUnits(), marginApplication.releasedUnits(), Instant.now());
        }
    }

    private long optionIntrinsicPriceTicks(OptionExerciseEvent event, ContractSpec spec, long underlyingPriceUnits) {
        if (!spec.contractType().isOption()) {
            throw new IllegalArgumentException("option exercise event must reference an option contract");
        }
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

    private Duration settlementPriceWindow() {
        AccountProperties.ExpiringSettlement settlementProperties = properties.getExpiringSettlement();
        if (settlementProperties == null) {
            return Duration.ZERO;
        }
        Duration window = settlementProperties.getSettlementPriceWindow();
        return window == null || window.isNegative() ? Duration.ZERO : window;
    }

    private Instant settlementTime(Instant deliveryTime, Instant eventTime) {
        return deliveryTime != null ? deliveryTime : eventTime;
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
                                           PositionSettlementState position,
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
                                        PositionSettlementState position) {
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
                                    long feeRatePpm,
                                    String feeReason,
                                    Instant eventTime) {
        SpotInstrumentSpec spec = spotInstrumentSpec(symbol, instrumentVersion);
        if (spotTradeSettlementService == null) {
            accountSettlementService.settleSpotTradeSide(userId, orderId, tradeId, symbol, side, priceTicks,
                    quantitySteps, spec, feeRatePpm, feeReason, orderCompleted, eventTime);
            return;
        }
        spotTradeSettlementService.settle(
                userId, orderId, tradeId, symbol, side, priceTicks, quantitySteps,
                spec, feeRatePpm, feeReason, orderCompleted, eventTime);
    }

    private AccountSettlementService.OrderMarginApplication applyTradeSide(long tradeId,
                                            long orderId,
                                            long userId,
                                            String symbol,
                                            long fillInstrumentVersion,
                                            OrderSide side,
                                            MarginMode marginMode,
                                            PositionSide positionSide,
                                            long priceTicks,
                                            long quantitySteps,
                                            long orderQuantitySteps,
                                            boolean reduceOnly,
                                            AccountType reservationAccountType,
                                            String reservationAsset,
                                            long reservedUnits,
                                            boolean orderCompleted,
                                            long feeRatePpm,
                                            String feeReason,
                                            Instant eventTime,
                                            String traceId) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        ContractSpec fillSpec = contractSpec(symbol, fillInstrumentVersion);
        ProductLine productLine = ProductLine.requireContractTypeCode(fillSpec.contractType().name());
        PositionState current = accountSettlementService.lockPosition(productLine, userId, symbol, normalizedMarginMode,
                normalizedPositionSide);
        ContractSpec positionSpec = current.signedQuantitySteps() == 0
                ? fillSpec
                : contractSpec(symbol, current.instrumentVersion());
        long closeSteps = MarginTransferMath.closeSteps(current.signedQuantitySteps(), side, quantitySteps);
        long openSteps = Math.subtractExact(quantitySteps, closeSteps);
        AccountSettlementService.OrderMarginApplication marginApplication = AccountSettlementService.OrderMarginApplication.NONE;
        PositionChange change = positionCalculator.apply(current, side, priceTicks, quantitySteps,
                positionSpec, fillSpec);
        if (fillSpec.contractType().isOption()) {
            marginApplication = accountSettlementService.settleOptionPremium(derivativeAccountType(fillSpec), side, userId,
                    fillSpec.settleAsset(), orderId, tradeId, symbol, normalizedMarginMode,
                    MarginTransferMath.optionPremiumUnits(fillSpec, priceTicks, quantitySteps),
                    reservationAccountType, reservationAsset, reservedUnits,
                    orderQuantitySteps, quantitySteps, eventTime);
        }
        if (closeSteps > 0 && !positionSpec.contractType().isOption()) {
            accountSettlementService.settleRealizedPnl(derivativeAccountType(positionSpec), userId,
                    positionSpec.settleAsset(), orderId, tradeId, symbol, normalizedMarginMode,
                    change.realizedPnlDeltaUnits(), eventTime);
        }
        // A reversal first releases collateral belonging to the old position. Releasing after
        // consuming the opening leg would treat the new collateral as old collateral and release it too.
        if (closeSteps > 0) {
            accountSettlementService.releasePositionMargin(productLine, userId, symbol, normalizedMarginMode, closeSteps,
                    normalizedPositionSide, Math.absExact(current.signedQuantitySteps()), eventTime);
        }
        if (!fillSpec.contractType().isOption() || side == OrderSide.SELL) {
            long actualMarginUnits = openSteps == 0L ? 0L
                    : MarginTransferMath.openingInitialMarginUnits(fillSpec, priceTicks, openSteps);
            marginApplication = accountSettlementService.applyOrderMargin(productLine, orderId, reservationAccountType,
                    userId, symbol, normalizedMarginMode, normalizedPositionSide, reservationAsset, reservedUnits,
                    orderQuantitySteps, quantitySteps, openSteps, actualMarginUnits, reduceOnly, eventTime);
        }
        long feeDeltaUnits = TradeFeeMath.feeDeltaUnits(fillSpec, priceTicks, quantitySteps, feeRatePpm);
        accountSettlementService.settleTradeFee(derivativeAccountType(fillSpec), userId, fillSpec.settleAsset(),
                orderId, tradeId, feeDeltaUnits, feeReason, feeRatePpm, symbol,
                normalizedMarginMode, eventTime);
        if (closeSteps > 0) {
            settleLiquidationFeeIfNeeded(tradeId, orderId, userId, symbol, normalizedMarginMode, fillSpec,
                    priceTicks, quantitySteps, eventTime, traceId);
        }
        PositionResponse updated = accountSettlementService.updatePosition(productLine, userId, symbol, normalizedMarginMode,
                normalizedPositionSide,
                change.next(), current.signedQuantitySteps(), eventTime);
        if (outboxService != null) {
            var event = outboxService.enqueuePositionUpdated(properties.getKafka().getPositionEventsTopic(),
                    tradeId, updated, eventTime, traceId);
            schedulePositionCacheSync(event.cacheEvent());
        } else {
            schedulePositionCacheSync(productLine, userId, symbol, normalizedMarginMode, normalizedPositionSide);
        }
        return marginApplication;
    }

    private void schedulePositionCacheSync(com.surprising.account.api.model.PositionCacheEvent snapshot) {
        if (positionCacheAfterCommitSynchronizer != null) {
            positionCacheAfterCommitSynchronizer.schedule(snapshot);
        }
    }

    private void schedulePositionCacheSync(ProductLine productLine,
                                           long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide) {
        if (positionCacheAfterCommitSynchronizer != null) {
            positionCacheAfterCommitSynchronizer.schedule(productLine, userId, symbol, marginMode, positionSide);
        }
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
            accountSettlementService.settleLiquidationFee(accountType, userId, fillSpec.settleAsset(),
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
        if (outboxService == null || settlement.collectedFeeUnits() <= 0) {
            return;
        }
        outboxService.enqueueLiquidationFeeSettled(properties.getKafka().getLiquidationFeeEventsTopic(),
                tradeId, orderId, settlement.liquidationOrderId(), settlement.candidateId(), userId, symbol,
                marginMode, accountType.name(), asset, settlement.collectedFeeUnits(), settlement.feeRatePpm(),
                eventTime, traceId);
    }

    private ContractSpec contractSpec(String symbol, long instrumentVersion) {
        return contractSpecCache.get(new ContractSpecKey(symbol, instrumentVersion),
                key -> accountSettlementService.contractSpec(key.symbol(), key.instrumentVersion()));
    }

    private InstrumentType instrumentType(String symbol, long instrumentVersion) {
        return instrumentTypeCache.get(new ContractSpecKey(symbol, instrumentVersion),
                key -> accountSettlementService.instrumentType(key.symbol(), key.instrumentVersion()));
    }

    private SpotInstrumentSpec spotInstrumentSpec(String symbol, long instrumentVersion) {
        return spotInstrumentSpecCache.get(new ContractSpecKey(symbol, instrumentVersion),
                key -> accountInstrumentRepository == null
                        ? accountSettlementService.spotInstrumentSpec(key.symbol(), key.instrumentVersion())
                        : accountInstrumentRepository.findSpotSpec(key.symbol(), key.instrumentVersion())
                                .orElseThrow(() -> new IllegalStateException(
                                        "spot instrument spec not found for "
                                                + key.symbol() + " version " + key.instrumentVersion())));
    }

    private Optional<LiquidationFeeContext> liquidationFeeContext(long orderId, long userId, String symbol) {
        return liquidationFeeContextCache.get(new LiquidationFeeContextKey(orderId, userId, symbol),
                key -> accountSettlementService.liquidationFeeContext(key.orderId(), key.userId(), key.symbol()));
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

    private void recordAdminBalanceAdjustment(String adjustmentKind,
                                              long adminUserId,
                                              String adminUsername,
                                              long userId,
                                              AccountType accountType,
                                              String asset,
                                              long amountUnits,
                                              long balanceAfterUnits,
                                              String referenceId,
                                              String reason) {
        if (adminBalanceAdjustmentRepository == null) {
            accountSettlementService.recordAdminBalanceAdjustment(adjustmentKind, adminUserId, adminUsername, userId,
                    accountType, asset, amountUnits, balanceAfterUnits, referenceId, reason);
            return;
        }
        adminBalanceAdjustmentRepository.record(adjustmentKind, adminUserId, adminUsername, userId,
                accountType, asset, amountUnits, balanceAfterUnits, referenceId, reason);
    }

    private BalanceResponse adjustBalance(long userId,
                                          String asset,
                                          long amountUnits,
                                          String referenceId,
                                          String reason) {
        if (accountBalanceCommandService == null) {
            return accountSettlementService.adjustBalance(userId, asset, amountUnits, referenceId, reason);
        }
        return accountBalanceCommandService.adjustBalance(userId, asset, amountUnits, referenceId, reason);
    }

    private ProductBalanceResponse adjustProductBalance(long userId,
                                                        AccountType accountType,
                                                        String asset,
                                                        long amountUnits,
                                                        String referenceId,
                                                        String reason) {
        if (accountBalanceCommandService == null) {
            return accountSettlementService.adjustProductBalance(
                    userId, accountType, asset, amountUnits, referenceId, reason);
        }
        return accountBalanceCommandService.adjustProductBalance(
                userId, accountType, asset, amountUnits, referenceId, reason);
    }

    private Optional<PositionResponse> findPosition(ProductLine productLine,
                                                    long userId,
                                                    String symbol,
                                                    MarginMode marginMode,
                                                    PositionSide positionSide) {
        if (positionRepository == null) {
            return productLine == null
                    ? accountSettlementService.position(userId, symbol, marginMode, positionSide)
                    : accountSettlementService.position(productLine, userId, symbol, marginMode, positionSide);
        }
        return productLine == null
                ? positionRepository.find(userId, symbol, marginMode, positionSide)
                : positionRepository.find(productLine, userId, symbol, marginMode, positionSide);
    }

    private List<PositionResponse> findPositions(ProductLine productLine,
                                                 long userId,
                                                 PositionSide positionSide) {
        if (positionRepository == null) {
            return productLine == null
                    ? accountSettlementService.positions(userId, positionSide)
                    : accountSettlementService.positions(productLine, userId, positionSide);
        }
        return productLine == null
                ? positionRepository.findOpenByUser(userId, positionSide)
                : positionRepository.findOpenByUser(productLine, userId, positionSide);
    }

    private List<PositionSettlementState> lockOpenPositionStatesForSettlement(ProductLine productLine,
                                                                               String symbol) {
        if (positionRepository == null) {
            return accountSettlementService.openPositionStatesForSettlement(productLine, symbol);
        }
        return positionRepository.lockOpenStatesForSettlement(productLine, symbol);
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

    private record LiquidationFeeContextKey(long orderId, long userId, String symbol) {
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
