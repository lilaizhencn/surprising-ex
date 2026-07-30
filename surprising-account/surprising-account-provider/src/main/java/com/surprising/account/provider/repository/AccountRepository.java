package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.account.api.model.AdminBalanceAdjustmentRecord;
import com.surprising.account.api.model.AdminCursorPage;
import com.surprising.account.api.model.AccountLedgerEntryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductLedgerEntryResponse;
import com.surprising.account.api.model.ProductTransferRecordResponse;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.model.BalanceDebitResult;
import com.surprising.account.provider.model.BalanceSettlementState;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.LiquidationFeeContext;
import com.surprising.account.provider.model.LiquidationFeeSettlement;
import com.surprising.account.provider.model.PositionSettlementState;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.account.provider.service.AccountBalanceCommandService;
import com.surprising.account.provider.service.AccountQueryService;
import com.surprising.account.provider.service.MarginTransferMath;
import com.surprising.account.provider.service.PnlSettlementMath;
import com.surprising.account.provider.service.PositionCacheAfterCommitSynchronizer;
import com.surprising.account.provider.service.PositionModeCommandService;
import com.surprising.account.provider.service.PositionModeSwitchGuard;
import com.surprising.account.provider.service.PositionOpenInterestService;
import com.surprising.account.provider.service.PositionQueryService;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineSql;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountRepository {

    private static final long PPM = 1_000_000L;
    private final JdbcTemplate jdbcTemplate;
    private final AccountSequenceRepository sequenceRepository;
    private final LatestMarkPriceCache markPriceCache;
    private final PositionCacheAfterCommitSynchronizer positionCacheSynchronizer;
    private final AccountLedgerRepository accountLedgerRepository;
    private final ProductLedgerRepository productLedgerRepository;
    private final AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository;
    private final ProductTransferRepository productTransferRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final AccountDeficitRepository accountDeficitRepository;
    private final ProductBalanceRepository productBalanceRepository;
    private final ProductDeficitRepository productDeficitRepository;
    private final AccountBalanceCommandService accountBalanceCommandService;
    private final PositionModeRepository positionModeRepository;
    private final TradeSettlementSideRepository tradeSettlementSideRepository;
    private final PositionModeCommandService positionModeCommandService;
    private final PositionRepository positionRepository;
    private final PositionMarginRepository positionMarginRepository;
    private final AccountInstrumentRepository accountInstrumentRepository;
    private final PositionQueryService positionQueryService;
    private final OpenInterestShardRepository openInterestShardRepository;
    private final PositionOpenInterestService positionOpenInterestService;

    public AccountRepository(JdbcTemplate jdbcTemplate, AccountSequenceRepository sequenceRepository) {
        this(jdbcTemplate, sequenceRepository, null, null);
    }

    public AccountRepository(JdbcTemplate jdbcTemplate,
                             AccountSequenceRepository sequenceRepository,
                             LatestMarkPriceCache markPriceCache) {
        this(jdbcTemplate, sequenceRepository, markPriceCache, null);
    }

    public AccountRepository(JdbcTemplate jdbcTemplate,
                             AccountSequenceRepository sequenceRepository,
                             LatestMarkPriceCache markPriceCache,
                             PositionCacheAfterCommitSynchronizer positionCacheSynchronizer) {
        this(jdbcTemplate, sequenceRepository, markPriceCache, positionCacheSynchronizer,
                new AccountLedgerRepository(jdbcTemplate),
                new ProductLedgerRepository(jdbcTemplate),
                new AdminBalanceAdjustmentRepository(jdbcTemplate),
                new ProductTransferRepository(jdbcTemplate),
                new AccountBalanceRepository(jdbcTemplate),
                new AccountDeficitRepository(jdbcTemplate),
                new ProductBalanceRepository(jdbcTemplate),
                new ProductDeficitRepository(jdbcTemplate),
                null,
                new PositionModeRepository(jdbcTemplate),
                new TradeSettlementSideRepository(jdbcTemplate),
                null,
                new PositionRepository(jdbcTemplate),
                new PositionMarginRepository(jdbcTemplate),
                new AccountInstrumentRepository(jdbcTemplate),
                null,
                new OpenInterestShardRepository(jdbcTemplate),
                null);
    }

    @Autowired
    public AccountRepository(JdbcTemplate jdbcTemplate,
                             AccountSequenceRepository sequenceRepository,
                             LatestMarkPriceCache markPriceCache,
                             PositionCacheAfterCommitSynchronizer positionCacheSynchronizer,
                             AccountLedgerRepository accountLedgerRepository,
                             ProductLedgerRepository productLedgerRepository,
                             AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository,
                             ProductTransferRepository productTransferRepository,
                             AccountBalanceRepository accountBalanceRepository,
                             AccountDeficitRepository accountDeficitRepository,
                             ProductBalanceRepository productBalanceRepository,
                             ProductDeficitRepository productDeficitRepository,
                             AccountBalanceCommandService accountBalanceCommandService,
                             PositionModeRepository positionModeRepository,
                             TradeSettlementSideRepository tradeSettlementSideRepository,
                             PositionModeCommandService positionModeCommandService,
                             PositionRepository positionRepository,
                             PositionMarginRepository positionMarginRepository,
                             AccountInstrumentRepository accountInstrumentRepository,
                             PositionQueryService positionQueryService,
                             OpenInterestShardRepository openInterestShardRepository,
                             PositionOpenInterestService positionOpenInterestService) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
        this.markPriceCache = markPriceCache;
        this.positionCacheSynchronizer = positionCacheSynchronizer;
        this.accountLedgerRepository = accountLedgerRepository;
        this.productLedgerRepository = productLedgerRepository;
        this.adminBalanceAdjustmentRepository = adminBalanceAdjustmentRepository;
        this.productTransferRepository = productTransferRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.accountDeficitRepository = accountDeficitRepository;
        this.productBalanceRepository = productBalanceRepository;
        this.productDeficitRepository = productDeficitRepository;
        if (accountBalanceCommandService == null) {
            AccountQueryService queryService = new AccountQueryService(
                    accountLedgerRepository,
                    productLedgerRepository,
                    productTransferRepository,
                    adminBalanceAdjustmentRepository,
                    accountBalanceRepository,
                    accountDeficitRepository,
                    productBalanceRepository,
                    productDeficitRepository);
            this.accountBalanceCommandService = new AccountBalanceCommandService(
                    sequenceRepository,
                    accountLedgerRepository,
                    productLedgerRepository,
                    productTransferRepository,
                    accountBalanceRepository,
                    productBalanceRepository,
                    queryService);
        } else {
            this.accountBalanceCommandService = accountBalanceCommandService;
        }
        this.positionModeRepository = positionModeRepository;
        this.tradeSettlementSideRepository = tradeSettlementSideRepository;
        this.positionModeCommandService = positionModeCommandService == null
                ? new PositionModeCommandService(positionModeRepository, new PositionModeSwitchGuard(jdbcTemplate))
                : positionModeCommandService;
        this.positionRepository = positionRepository;
        this.positionMarginRepository = positionMarginRepository;
        this.accountInstrumentRepository = accountInstrumentRepository;
        this.positionQueryService = positionQueryService == null
                ? new PositionQueryService(positionRepository, positionMarginRepository, accountInstrumentRepository)
                : positionQueryService;
        this.openInterestShardRepository = openInterestShardRepository;
        this.positionOpenInterestService = positionOpenInterestService == null
                ? new PositionOpenInterestService(jdbcTemplate, positionRepository, openInterestShardRepository)
                : positionOpenInterestService;
    }

    public Optional<BalanceResponse> balance(long userId, String asset) {
        return accountBalanceRepository.find(userId, asset)
                .map(row -> toBalance(row, accountDeficitRepository.findUnits(userId, asset).orElse(0L)));
    }

    public List<BalanceResponse> balances(long userId) {
        java.util.Map<String, Long> deficits = new java.util.HashMap<>();
        accountDeficitRepository.findByUser(userId)
                .forEach(row -> deficits.put(row.asset(), row.deficitUnits()));
        return accountBalanceRepository.findByUser(userId).stream()
                .map(row -> toBalance(row, deficits.getOrDefault(row.asset(), 0L)))
                .toList();
    }

    public Optional<ProductBalanceResponse> productBalance(long userId, AccountType accountType, String asset) {
        AccountType normalizedType = requireAccountType(accountType);
        if (isLegacyPerpetualAccount(normalizedType)) {
            return balance(userId, asset).map(balance -> toProductBalance(normalizedType, balance));
        }
        return productBalanceRepository.find(userId, normalizedType, asset)
                .map(row -> toProductBalance(row,
                        productDeficitRepository.findUnits(userId, normalizedType, asset).orElse(0L)));
    }

    public List<ProductBalanceResponse> productBalances(long userId, AccountType accountType) {
        if (accountType != null && isLegacyPerpetualAccount(accountType)) {
            return balances(userId).stream()
                    .map(balance -> toProductBalance(accountType, balance))
                    .toList();
        }
        if (accountType != null) {
            return productBalancesFromTable(userId, accountType);
        }
        List<ProductBalanceResponse> legacyBalances = balances(userId).stream()
                .map(balance -> toProductBalance(AccountType.USDT_PERPETUAL, balance))
                .toList();
        List<ProductBalanceResponse> isolatedBalances = productBalancesFromTable(userId, null);
        return java.util.stream.Stream.concat(legacyBalances.stream(), isolatedBalances.stream())
                .toList();
    }

    public List<AccountLedgerEntryResponse> accountLedger(Long userId,
                                                          String asset,
                                                          String referenceType,
                                                          int limit) {
        return accountLedgerRepository.entries(userId, asset, referenceType, limit);
    }

    public AdminCursorPage.CursorPage<AccountLedgerEntryResponse> accountLedgerPage(Long userId,
                                                                                    String asset,
                                                                                    String referenceType,
                                                                                    int limit,
                                                                                    String cursor,
                                                                                    String sort) {
        return accountLedgerRepository.page(userId, asset, referenceType, limit, cursor, sort);
    }

    public List<ProductLedgerEntryResponse> productLedger(Long userId,
                                                          AccountType accountType,
                                                          String asset,
                                                          String referenceType,
                                                          int limit) {
        return productLedgerRepository.entries(userId, accountType, asset, referenceType, limit);
    }

    public AdminCursorPage.CursorPage<ProductLedgerEntryResponse> productLedgerPage(Long userId,
                                                                                    AccountType accountType,
                                                                                    String asset,
                                                                                    String referenceType,
                                                                                    int limit,
                                                                                    String cursor,
                                                                                    String sort) {
        return productLedgerRepository.page(userId, accountType, asset, referenceType, limit, cursor, sort);
    }

    public List<ProductTransferRecordResponse> productTransfers(Long userId,
                                                                AccountType accountType,
                                                                String asset,
                                                                int limit) {
        return productTransferRepository.entries(userId, accountType, asset, limit);
    }

    public AdminCursorPage.CursorPage<ProductTransferRecordResponse> productTransferPage(Long userId,
                                                                                         AccountType accountType,
                                                                                         String asset,
                                                                                         int limit,
                                                                                         String cursor,
                                                                                         String sort) {
        return productTransferRepository.page(userId, accountType, asset, limit, cursor, sort);
    }

    public AdminBalanceAdjustmentRecord recordAdminBalanceAdjustment(String adjustmentKind,
                                                                     long adminUserId,
                                                                     String adminUsername,
                                                                     long userId,
                                                                     AccountType accountType,
                                                                     String asset,
                                                                     long amountUnits,
                                                                     long balanceAfterUnits,
                                                                     String referenceId,
                                                                     String reason) {
        return adminBalanceAdjustmentRepository.record(adjustmentKind, adminUserId, adminUsername, userId,
                accountType, asset, amountUnits, balanceAfterUnits, referenceId, reason);
    }

    public List<AdminBalanceAdjustmentRecord> adminBalanceAdjustments(Long adminUserId,
                                                                      Long userId,
                                                                      String adjustmentKind,
                                                                      AccountType accountType,
                                                                      String asset,
                                                                      String referenceId,
                                                                      int limit) {
        return adminBalanceAdjustmentRepository.entries(adminUserId, userId, adjustmentKind, accountType,
                asset, referenceId, limit);
    }

    public AdminCursorPage.CursorPage<AdminBalanceAdjustmentRecord> adminBalanceAdjustmentPage(Long adminUserId,
                                                                                               Long userId,
                                                                                               String adjustmentKind,
                                                                                               AccountType accountType,
                                                                                               String asset,
                                                                                               String referenceId,
                                                                                               int limit,
                                                                                               String cursor,
                                                                                               String sort) {
        return adminBalanceAdjustmentRepository.page(adminUserId, userId, adjustmentKind, accountType,
                asset, referenceId, limit, cursor, sort);
    }

    private List<ProductBalanceResponse> productBalancesFromTable(long userId, AccountType accountType) {
        java.util.Map<ProductBalanceKey, Long> deficits = new java.util.HashMap<>();
        productDeficitRepository.findByUser(userId, accountType)
                .forEach(row -> deficits.put(new ProductBalanceKey(row.accountType(), row.asset()),
                        row.deficitUnits()));
        return productBalanceRepository.findByUser(userId, accountType).stream()
                .filter(row -> accountType != null || row.accountType() != AccountType.USDT_PERPETUAL)
                .map(row -> toProductBalance(row,
                        deficits.getOrDefault(new ProductBalanceKey(row.accountType(), row.asset()), 0L)))
                .toList();
    }

    public ProductBalanceResponse adjustProductBalance(long userId,
                                                       AccountType accountType,
                                                       String asset,
                                                       long amountUnits,
                                                       String referenceId,
                                                       String reason) {
        return accountBalanceCommandService.adjustProductBalance(
                userId, accountType, asset, amountUnits, referenceId, reason);
    }

    public ProductTransferResponse transferProductBalance(long userId,
                                                          AccountType sourceAccountType,
                                                          AccountType targetAccountType,
                                                          String asset,
                                                          long amountUnits,
                                                          String referenceId,
                                                          String reason) {
        return accountBalanceCommandService.transferProductBalance(
                userId, sourceAccountType, targetAccountType, asset, amountUnits, referenceId, reason);
    }

    public BalanceResponse adjustBalance(long userId, String asset, long amountUnits, String referenceId, String reason) {
        return accountBalanceCommandService.adjustBalance(userId, asset, amountUnits, referenceId, reason);
    }

    public Optional<PositionResponse> position(long userId, String symbol, MarginMode marginMode) {
        return position(userId, symbol, marginMode, PositionSide.NET);
    }

    public PositionModeResponse positionMode(long userId) {
        return positionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    public PositionModeResponse positionMode(ProductLine productLine, long userId) {
        ProductLine resolvedProductLine = productLine(productLine);
        return positionModeRepository.find(resolvedProductLine, userId)
                .map(row -> new PositionModeResponse(
                        resolvedProductLine, userId, row.positionMode(), row.updatedAt()))
                .orElse(new PositionModeResponse(resolvedProductLine, userId, PositionMode.ONE_WAY, Instant.EPOCH));
    }

    @Transactional
    public PositionModeResponse updatePositionMode(long userId, PositionMode positionMode, Instant now) {
        return updatePositionMode(ProductLine.LINEAR_PERPETUAL, userId, positionMode, now);
    }

    @Transactional
    public PositionModeResponse updatePositionMode(ProductLine productLine,
                                                   long userId,
                                                   PositionMode positionMode,
                                                   Instant now) {
        return positionModeCommandService.update(productLine, userId, positionMode, now);
    }

    public Optional<PositionResponse> position(long userId, String symbol, MarginMode marginMode,
                                               PositionSide positionSide) {
        return positionRepository.find(userId, symbol, marginMode, positionSide);
    }

    public Optional<PositionResponse> position(ProductLine productLine,
                                               long userId,
                                               String symbol,
                                               MarginMode marginMode,
        PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        return positionRepository.find(resolvedProductLine, userId, symbol, marginMode, positionSide);
    }

    public List<PositionResponse> positions(long userId) {
        return positions(userId, null);
    }

    public List<PositionResponse> positions(long userId, PositionSide positionSide) {
        return positionRepository.findOpenByUser(userId, positionSide);
    }

    public List<PositionResponse> positions(ProductLine productLine, long userId, PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        return positionRepository.findOpenByUser(resolvedProductLine, userId, positionSide);
    }

    public List<PositionResponse> openPositionsForSettlement(String symbol, long instrumentVersion) {
        return positionRepository.lockOpenForSettlement(symbol, instrumentVersion);
    }

    public List<PositionSettlementState> openPositionStatesForSettlement(String symbol, long instrumentVersion) {
        return positionRepository.lockOpenStatesForSettlement(symbol, instrumentVersion);
    }

    public List<PositionResponse> openPositionsForSettlement(ProductLine productLine, String symbol) {
        ProductLine resolvedProductLine = productLine(productLine);
        return positionRepository.lockOpenForSettlement(resolvedProductLine, symbol);
    }

    public List<PositionSettlementState> openPositionStatesForSettlement(ProductLine productLine, String symbol) {
        ProductLine resolvedProductLine = productLine(productLine);
        return positionRepository.lockOpenStatesForSettlement(resolvedProductLine, symbol);
    }

    public Optional<PositionMarginResponse> positionMargin(long userId, String symbol, MarginMode marginMode) {
        return positionMargin(userId, symbol, marginMode, PositionSide.NET);
    }

    public Optional<PositionMarginResponse> positionMargin(long userId, String symbol, MarginMode marginMode,
                                                          PositionSide positionSide) {
        return positionQueryService.positionMargin(userId, symbol, marginMode, positionSide);
    }

    public Optional<PositionMarginResponse> positionMargin(ProductLine productLine,
                                                           long userId,
                                                           String symbol,
                                                           MarginMode marginMode,
        PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        return positionQueryService.positionMargin(
                resolvedProductLine, userId, symbol, marginMode, positionSide);
    }

    public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(long userId,
                                                                         String symbol,
                                                                         long amountUnits,
                                                                         String referenceId,
                                                                         String reason,
                                                                         Duration maxRiskSnapshotAge,
                                                                         long removalBufferPpm) {
        return adjustIsolatedPositionMargin(userId, symbol, PositionSide.NET, amountUnits, referenceId, reason,
                maxRiskSnapshotAge, removalBufferPpm);
    }

    public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(ProductLine productLine,
                                                                         long userId,
                                                                         String symbol,
                                                                         long amountUnits,
                                                                         String referenceId,
                                                                         String reason,
                                                                         Duration maxRiskSnapshotAge,
                                                                         long removalBufferPpm) {
        return adjustIsolatedPositionMargin(productLine, userId, symbol, PositionSide.NET, amountUnits, referenceId,
                reason, maxRiskSnapshotAge, removalBufferPpm);
    }

    public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(ProductLine productLine,
                                                                         long userId,
                                                                         String symbol,
                                                                         PositionSide positionSide,
                                                                         long amountUnits,
                                                                         String referenceId,
                                                                         String reason,
                                                                         Duration maxRiskSnapshotAge,
                                                                         long removalBufferPpm) {
        return adjustIsolatedPositionMarginScoped(productLine(productLine), userId, symbol, positionSide,
                amountUnits, referenceId, reason, maxRiskSnapshotAge, removalBufferPpm);
    }

    public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(long userId,
                                                                         String symbol,
                                                                         PositionSide positionSide,
                                                                         long amountUnits,
                                                                         String referenceId,
                                                                         String reason,
                                                                         Duration maxRiskSnapshotAge,
                                                                         long removalBufferPpm) {
        return adjustIsolatedPositionMarginScoped(null, userId, symbol, positionSide, amountUnits, referenceId,
                reason, maxRiskSnapshotAge, removalBufferPpm);
    }

    private PositionMarginAdjustmentResponse adjustIsolatedPositionMarginScoped(ProductLine productLine,
                                                                                long userId,
                                                                                String symbol,
                                                                                PositionSide positionSide,
                                                                                long amountUnits,
                                                                                String referenceId,
                                                                                String reason,
                                                                                Duration maxRiskSnapshotAge,
                                                                                long removalBufferPpm) {
        Optional<PositionMarginAdjustmentReference> existing =
                productLine == null
                        ? positionMarginAdjustmentReference(userId, symbol, referenceId)
                        : productPositionMarginAdjustmentReference(accountType(productLine), userId, symbol, referenceId);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        if (existing.isPresent()) {
            requirePositionMarginAdjustmentMatches(existing.get(), amountUnits, reason, symbol);
            return productLine == null
                    ? positionMarginAdjustmentResponse(userId, symbol, existing.get().asset(), normalizedPositionSide,
                            amountUnits, referenceId)
                    : positionMarginAdjustmentResponse(productLine, userId, symbol, existing.get().asset(),
                            normalizedPositionSide, amountUnits, referenceId);
        }

        PositionCollateralTarget target = productLine == null
                ? lockOpenIsolatedPosition(userId, symbol, normalizedPositionSide)
                : lockOpenIsolatedPosition(productLine, userId, symbol, normalizedPositionSide);
        AccountType productAccountType = productLine == null ? null : accountType(productLine);
        int ledgerRows = productLine == null
                ? insertPositionMarginAdjustmentLedger(userId, target.asset(), amountUnits, referenceId, reason, symbol)
                : insertProductPositionMarginAdjustmentLedger(productAccountType, userId, target.asset(),
                        amountUnits, referenceId, reason, symbol);
        if (ledgerRows == 0) {
            Optional<PositionMarginAdjustmentReference> duplicateReference = productLine == null
                    ? positionMarginAdjustmentReferenceByAsset(userId, target.asset(), referenceId)
                    : productPositionMarginAdjustmentReferenceByAsset(productAccountType, userId, target.asset(),
                            referenceId);
            PositionMarginAdjustmentReference duplicate = duplicateReference
                    .orElseThrow(() -> new IllegalStateException("duplicate position margin adjustment but ledger missing"));
            requirePositionMarginAdjustmentMatches(duplicate, amountUnits, reason, symbol);
            return productLine == null
                    ? positionMarginAdjustmentResponse(userId, symbol, target.asset(), normalizedPositionSide,
                            amountUnits, referenceId)
                    : positionMarginAdjustmentResponse(productLine, userId, symbol, target.asset(),
                            normalizedPositionSide, amountUnits, referenceId);
        }

        Instant now = Instant.now();
        ProductLine resolvedProductLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        long currentMarginUnits = lockPositionMarginUnits(resolvedProductLine, userId, symbol, target.asset(),
                MarginMode.ISOLATED, normalizedPositionSide);
        if (amountUnits > 0) {
            if (productLine == null) {
                addIsolatedPositionMargin(userId, symbol, target.asset(), normalizedPositionSide, amountUnits, now);
            } else {
                addProductIsolatedPositionMargin(productAccountType, resolvedProductLine, userId, symbol,
                        target.asset(), normalizedPositionSide, amountUnits, now);
            }
        } else {
            long removeUnits = Math.absExact(amountUnits);
            validateIsolatedMarginRemoval(target, currentMarginUnits, removeUnits, maxRiskSnapshotAge,
                    removalBufferPpm);
            if (productLine == null) {
                removeIsolatedPositionMargin(userId, symbol, target.asset(), normalizedPositionSide, removeUnits, now);
            } else {
                removeProductIsolatedPositionMargin(productAccountType, resolvedProductLine, userId, symbol,
                        target.asset(), normalizedPositionSide, removeUnits, now);
            }
        }

        PositionMarginAdjustmentResponse response =
                productLine == null
                        ? positionMarginAdjustmentResponse(userId, symbol, target.asset(), normalizedPositionSide,
                                amountUnits, referenceId)
                        : positionMarginAdjustmentResponse(productLine, userId, symbol, target.asset(),
                                normalizedPositionSide, amountUnits, referenceId);
        int ledgerRowsAfter = productLine == null
                ? updatePositionMarginAdjustmentLedgerBalance(userId, target.asset(), referenceId,
                        response.equityUnits())
                : updateProductPositionMarginAdjustmentLedgerBalance(productAccountType, userId, target.asset(),
                        referenceId, response.equityUnits());
        requireSingleRow(ledgerRowsAfter, "position margin adjustment ledger update");
        return response;
    }

    public PositionState lockPosition(long userId, String symbol, MarginMode marginMode) {
        return lockPosition(userId, symbol, marginMode, PositionSide.NET);
    }

    public PositionState lockPosition(long userId, String symbol, MarginMode marginMode, PositionSide positionSide) {
        return lockPosition(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, positionSide);
    }

    public PositionState lockPosition(ProductLine productLine,
                                      long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        PositionRepository.LockedPosition locked = positionRepository.lockOrCreate(
                resolvedProductLine, userId, symbol, normalizedMarginMode, normalizedPositionSide, Instant.now());
        if (locked.created()) {
            schedulePositionCacheProjection(resolvedProductLine, userId, symbol,
                    normalizedMarginMode, normalizedPositionSide);
        }
        return locked.state();
    }

    private PositionCollateralTarget lockOpenIsolatedPosition(long userId, String symbol, PositionSide positionSide) {
        PositionRepository.LockedPositionTarget target = positionRepository
                .lockOpenIsolated(userId, symbol, positionSide)
                .orElseThrow(() -> new IllegalStateException("open isolated position not found"));
        String asset = accountInstrumentRepository.findSettleAsset(symbol, target.instrumentVersion())
                .orElseThrow(() -> new IllegalStateException("open isolated position not found"));
        return new PositionCollateralTarget(
                userId, symbol, asset, PositionSide.defaultIfNull(positionSide),
                target.instrumentVersion(), target.signedQuantitySteps());
    }

    private PositionCollateralTarget lockOpenIsolatedPosition(ProductLine productLine,
                                                              long userId,
                                                              String symbol,
        PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        PositionRepository.LockedPositionTarget target = positionRepository
                .lockOpenIsolated(resolvedProductLine, userId, symbol, positionSide)
                .orElseThrow(() -> new IllegalStateException("open isolated position not found"));
        String asset = accountInstrumentRepository.findSettleAsset(symbol, target.instrumentVersion())
                .orElseThrow(() -> new IllegalStateException("open isolated position not found"));
        return new PositionCollateralTarget(
                userId, symbol, asset, PositionSide.defaultIfNull(positionSide),
                target.instrumentVersion(), target.signedQuantitySteps());
    }

    private long lockPositionMarginUnits(long userId, String symbol, String asset, MarginMode marginMode) {
        return lockPositionMarginUnits(userId, symbol, asset, marginMode, PositionSide.NET);
    }

    private long lockPositionMarginUnits(long userId, String symbol, String asset, MarginMode marginMode,
                                         PositionSide positionSide) {
        return lockPositionMarginUnits(ProductLine.LINEAR_PERPETUAL, userId, symbol, asset, marginMode, positionSide);
    }

    private long lockPositionMarginUnits(ProductLine productLine, long userId, String symbol, String asset,
                                         MarginMode marginMode, PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        return positionMarginRepository.lockUnits(
                resolvedProductLine, userId, symbol, asset, marginMode, positionSide);
    }

    private void addIsolatedPositionMargin(long userId,
                                           String symbol,
                                           String asset,
                                           PositionSide positionSide,
                                           long amountUnits,
                                           Instant now) {
        addIsolatedPositionMargin(ProductLine.LINEAR_PERPETUAL, userId, symbol, asset, positionSide, amountUnits, now);
    }

    private void addIsolatedPositionMargin(ProductLine productLine,
                                           long userId,
                                           String symbol,
                                           String asset,
                                           PositionSide positionSide,
                                           long amountUnits,
                                           Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        int marginRows = positionMarginRepository.add(
                resolvedProductLine, userId, symbol, asset, MarginMode.ISOLATED,
                positionSide, amountUnits, now);
        requireSingleRow(marginRows, "isolated position margin add");
    }

    private void addProductIsolatedPositionMargin(AccountType accountType,
                                                  ProductLine productLine,
                                                  long userId,
                                                  String symbol,
                                                  String asset,
                                                  PositionSide positionSide,
                                                  long amountUnits,
                                                  Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalArgumentException("insufficient available product balance");
        }
        int marginRows = positionMarginRepository.add(
                resolvedProductLine, userId, symbol, asset, MarginMode.ISOLATED,
                positionSide, amountUnits, now);
        requireSingleRow(marginRows, "product isolated position margin add");
    }

    private void removeIsolatedPositionMargin(long userId,
                                              String symbol,
                                              String asset,
                                              PositionSide positionSide,
                                              long amountUnits,
                                              Instant now) {
        removeIsolatedPositionMargin(ProductLine.LINEAR_PERPETUAL, userId, symbol, asset, positionSide, amountUnits, now);
    }

    private void removeIsolatedPositionMargin(ProductLine productLine,
                                              long userId,
                                              String symbol,
                                              String asset,
                                              PositionSide positionSide,
                                              long amountUnits,
                                              Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        int marginRows = positionMarginRepository.subtract(
                resolvedProductLine, userId, symbol, asset, MarginMode.ISOLATED,
                positionSide, amountUnits, now);
        requireSingleRow(marginRows, "isolated position margin remove");
        positionMarginRepository.deleteZero(
                resolvedProductLine, userId, symbol, asset, MarginMode.ISOLATED, positionSide);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units + ?,
                       locked_units = locked_units - ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalStateException("insufficient locked balance for isolated margin removal");
        }
    }

    private void removeProductIsolatedPositionMargin(AccountType accountType,
                                                     ProductLine productLine,
                                                     long userId,
                                                     String symbol,
                                                     String asset,
                                                     PositionSide positionSide,
                                                     long amountUnits,
        Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        int marginRows = positionMarginRepository.subtract(
                resolvedProductLine, userId, symbol, asset, MarginMode.ISOLATED,
                positionSide, amountUnits, now);
        requireSingleRow(marginRows, "product isolated position margin remove");
        positionMarginRepository.deleteZero(
                resolvedProductLine, userId, symbol, asset, MarginMode.ISOLATED, positionSide);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units + ?,
                       locked_units = locked_units - ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        if (balanceRows != 1) {
            throw new IllegalStateException("insufficient locked product balance for isolated margin removal");
        }
    }

    private void validateIsolatedMarginRemoval(PositionCollateralTarget target,
                                               long currentMarginUnits,
                                               long removeUnits,
                                               Duration maxRiskSnapshotAge,
                                               long removalBufferPpm) {
        if (currentMarginUnits < removeUnits) {
            throw new IllegalArgumentException("insufficient isolated position margin");
        }
        RiskRemovalSnapshot snapshot = latestRiskRemovalSnapshot(target.userId(), target.symbol(),
                target.positionSide(),
                maxRiskSnapshotAge);
        if (snapshot.instrumentVersion() != target.instrumentVersion()
                || snapshot.signedQuantitySteps() != target.signedQuantitySteps()) {
            throw new IllegalStateException("risk snapshot is stale for isolated margin removal");
        }
        if ("LIQUIDATION".equals(snapshot.status())) {
            throw new IllegalStateException("position is already in liquidation risk");
        }
        long afterMarginUnits = Math.subtractExact(currentMarginUnits, removeUnits);
        long equityAfterUnits = Math.addExact(afterMarginUnits, snapshot.unrealizedPnlUnits());
        long requiredEquityUnits = requiredEquityWithBuffer(snapshot.maintenanceMarginUnits(), removalBufferPpm);
        if (equityAfterUnits < requiredEquityUnits) {
            throw new IllegalArgumentException("isolated margin removal would breach maintenance margin buffer");
        }
    }

    private RiskRemovalSnapshot latestRiskRemovalSnapshot(long userId, String symbol, PositionSide positionSide,
                                                          Duration maxRiskSnapshotAge) {
        return jdbcTemplate.query("""
                SELECT instrument_version,
                       signed_quantity_steps,
                       unrealized_pnl_units,
                       maintenance_margin_units,
                       status,
                       event_time
                  FROM risk_position_snapshots
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = 'ISOLATED'
                   AND position_side = ?
                   AND event_time >= now() - (? * INTERVAL '1 millisecond')
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> new RiskRemovalSnapshot(
                rs.getLong("instrument_version"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("unrealized_pnl_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getString("status"),
                rs.getTimestamp("event_time").toInstant()), userId, symbol,
                PositionSide.defaultIfNull(positionSide).name(), maxRiskSnapshotAge.toMillis())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("fresh risk snapshot not found for isolated margin removal"));
    }

    private long requiredEquityWithBuffer(long maintenanceMarginUnits, long removalBufferPpm) {
        if (removalBufferPpm < 0) {
            throw new IllegalArgumentException("removalBufferPpm must be non-negative");
        }
        BigInteger required = BigInteger.valueOf(maintenanceMarginUnits)
                .multiply(BigInteger.valueOf(Math.addExact(PPM, removalBufferPpm)))
                .add(BigInteger.valueOf(PPM - 1L))
                .divide(BigInteger.valueOf(PPM));
        return required.longValueExact();
    }

    private PositionMarginAdjustmentResponse positionMarginAdjustmentResponse(long userId,
                                                                              String symbol,
                                                                              String asset,
                                                                              long amountUnits,
                                                                              String referenceId) {
        return positionMarginAdjustmentResponse(userId, symbol, asset, PositionSide.NET, amountUnits, referenceId);
    }

    private PositionMarginAdjustmentResponse positionMarginAdjustmentResponse(long userId,
                                                                              String symbol,
                                                                              String asset,
                                                                              PositionSide positionSide,
                                                                              long amountUnits,
                                                                              String referenceId) {
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        long marginUnits = lockPositionMarginUnits(userId, symbol, asset, MarginMode.ISOLATED,
                normalizedPositionSide);
        BalanceResponse currentBalance = balance(userId, asset)
                .orElse(new BalanceResponse(userId, asset, 0L, 0L, 0L, Instant.EPOCH));
        return new PositionMarginAdjustmentResponse(userId, symbol, asset, MarginMode.ISOLATED,
                normalizedPositionSide, amountUnits,
                marginUnits, currentBalance.availableUnits(), currentBalance.lockedUnits(),
                currentBalance.equityUnits(), referenceId, currentBalance.updatedAt());
    }

    private PositionMarginAdjustmentResponse positionMarginAdjustmentResponse(ProductLine productLine,
                                                                              long userId,
                                                                              String symbol,
                                                                              String asset,
                                                                              PositionSide positionSide,
                                                                              long amountUnits,
                                                                              String referenceId) {
        ProductLine resolvedProductLine = productLine(productLine);
        AccountType accountType = accountType(resolvedProductLine);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        long marginUnits = lockPositionMarginUnits(resolvedProductLine, userId, symbol, asset, MarginMode.ISOLATED,
                normalizedPositionSide);
        ProductBalanceResponse currentBalance = productBalance(userId, accountType, asset)
                .orElse(new ProductBalanceResponse(userId, accountType, asset, 0L, 0L, 0L, Instant.EPOCH));
        return new PositionMarginAdjustmentResponse(userId, symbol, asset, MarginMode.ISOLATED,
                normalizedPositionSide, amountUnits,
                marginUnits, currentBalance.availableUnits(), currentBalance.lockedUnits(),
                currentBalance.equityUnits(), referenceId, currentBalance.updatedAt());
    }

    private int insertPositionMarginAdjustmentLedger(long userId,
                                                     String asset,
                                                     long amountUnits,
                                                     String referenceId,
                                                     String reason,
                                                     String symbol) {
        return jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, symbol, created_at
                ) VALUES (?, ?, ?, ?, 0, 'POSITION_MARGIN_ADJUSTMENT', ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY),
                userId, asset, amountUnits,
                referenceId, reason, symbol, Timestamp.from(Instant.now()));
    }

    private int insertProductPositionMarginAdjustmentLedger(AccountType accountType,
                                                            long userId,
                                                            String asset,
                                                            long amountUnits,
                                                            String referenceId,
                                                            String reason,
                                                            String symbol) {
        return jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, symbol, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'POSITION_MARGIN_ADJUSTMENT', ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.PRODUCT_LEDGER_ENTRY),
                userId, accountType.name(), asset,
                amountUnits, referenceId, reason, symbol, Timestamp.from(Instant.now()));
    }

    private int updatePositionMarginAdjustmentLedgerBalance(long userId,
                                                            String asset,
                                                            String referenceId,
                                                            long balanceAfterUnits) {
        return jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, asset);
    }

    private int updateProductPositionMarginAdjustmentLedgerBalance(AccountType accountType,
                                                                   long userId,
                                                                   String asset,
                                                                   String referenceId,
                                                                   long balanceAfterUnits) {
        return jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, accountType.name(), asset);
    }

    private Optional<PositionMarginAdjustmentReference> positionMarginAdjustmentReference(long userId,
                                                                                         String symbol,
                                                                                         String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND symbol = ?
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, symbol).stream().findFirst();
    }

    private Optional<PositionMarginAdjustmentReference> positionMarginAdjustmentReferenceByAsset(long userId,
                                                                                                String asset,
                                                                                                String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, asset).stream().findFirst();
    }

    private Optional<PositionMarginAdjustmentReference> productPositionMarginAdjustmentReference(AccountType accountType,
                                                                                                 long userId,
                                                                                                 String symbol,
                                                                                                 String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_product_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND symbol = ?
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, accountType.name(), symbol).stream().findFirst();
    }

    private Optional<PositionMarginAdjustmentReference> productPositionMarginAdjustmentReferenceByAsset(AccountType accountType,
                                                                                                        long userId,
                                                                                                        String asset,
                                                                                                        String referenceId) {
        return jdbcTemplate.query("""
                SELECT asset, amount_units, reason, symbol
                  FROM account_product_ledger_entries
                 WHERE reference_type = 'POSITION_MARGIN_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> new PositionMarginAdjustmentReference(
                rs.getString("asset"),
                rs.getLong("amount_units"),
                rs.getString("reason"),
                rs.getString("symbol")), referenceId, userId, accountType.name(), asset).stream().findFirst();
    }

    private void requirePositionMarginAdjustmentMatches(PositionMarginAdjustmentReference existing,
                                                        long amountUnits,
                                                        String reason,
                                                        String symbol) {
        if (existing.amountUnits() != amountUnits
                || !Objects.equals(existing.reason(), reason)
                || !Objects.equals(existing.symbol(), symbol)) {
            throw new IllegalStateException("conflicting duplicate position margin adjustment reference");
        }
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionState state,
                                           Instant now) {
        return updatePosition(userId, symbol, marginMode, PositionSide.NET, state, now);
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide,
                                           PositionState state,
                                           Instant now) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        long previousSignedQuantitySteps = lockCurrentPositionQuantity(userId, symbol, normalizedMarginMode,
                normalizedPositionSide);
        return updatePosition(userId, symbol, normalizedMarginMode, normalizedPositionSide, state,
                previousSignedQuantitySteps, now);
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionState state,
                                           long previousSignedQuantitySteps,
                                           Instant now) {
        return updatePosition(userId, symbol, marginMode, PositionSide.NET, state, previousSignedQuantitySteps, now);
    }

    public PositionResponse updatePosition(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide,
                                           PositionState state,
                                           long previousSignedQuantitySteps,
                                           Instant now) {
        return updatePosition(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, positionSide, state,
                previousSignedQuantitySteps, now);
    }

    public PositionResponse updatePosition(ProductLine productLine,
                                           long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide,
                                           PositionState state,
                                           long previousSignedQuantitySteps,
                                           Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        updatePositionAndOpenInterest(resolvedProductLine, userId, symbol, normalizedMarginMode,
                normalizedPositionSide, state, previousSignedQuantitySteps, now);
        schedulePositionCacheProjection(resolvedProductLine, userId, symbol,
                normalizedMarginMode, normalizedPositionSide);
        return new PositionResponse(userId, symbol, state.instrumentVersion(), normalizedMarginMode,
                normalizedPositionSide,
                state.signedQuantitySteps(), state.entryPriceTicks(), state.realizedPnlUnits(), now);
    }

    public void lockOpenInterestShards(List<OpenInterestLockRequest> requests, Instant now) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        List<OpenInterestShardRepository.OpenInterestShard> shards = requests.stream()
                .map(request -> new OpenInterestShardRepository.OpenInterestShard(
                        productLine(request.productLine()), request.symbol(),
                        OpenInterestShardRepository.shardId(request.userId())))
                .distinct()
                .sorted(Comparator.comparing(
                                (OpenInterestShardRepository.OpenInterestShard shard) ->
                                        shard.productLine().name())
                        .thenComparing(OpenInterestShardRepository.OpenInterestShard::symbol)
                        .thenComparingInt(OpenInterestShardRepository.OpenInterestShard::shardId))
                .toList();
        openInterestShardRepository.seedAndLock(shards, now);
    }

    private long lockCurrentPositionQuantity(long userId, String symbol, MarginMode marginMode) {
        return lockCurrentPositionQuantity(userId, symbol, marginMode, PositionSide.NET);
    }

    private long lockCurrentPositionQuantity(long userId, String symbol, MarginMode marginMode,
                                             PositionSide positionSide) {
        return lockCurrentPositionQuantity(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, positionSide);
    }

    private long lockCurrentPositionQuantity(ProductLine productLine, long userId, String symbol, MarginMode marginMode,
                                             PositionSide positionSide) {
        ProductLine resolvedProductLine = productLine(productLine);
        return positionRepository.lockCurrentQuantity(
                resolvedProductLine, userId, symbol, marginMode, positionSide);
    }

    private void updatePositionAndOpenInterest(ProductLine productLine,
                                               long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionSide positionSide,
                                               PositionState state,
                                               long previousSignedQuantitySteps,
                                               Instant now) {
        positionOpenInterestService.update(
                productLine, userId, symbol, marginMode, positionSide,
                state, previousSignedQuantitySteps, now);
    }

    public ContractSpec contractSpec(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT i.version,
                       i.contract_type,
                       i.settle_asset,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       i.initial_margin_rate_ppm,
                       i.maker_fee_rate_ppm,
                       i.taker_fee_rate_ppm,
                       ss.scale_units AS settle_scale_units
                  FROM instruments i
                  JOIN account_asset_scales ss
                    ON ss.asset = i.settle_asset
                 WHERE i.symbol = ?
                   AND i.version = ?
                """, (rs, rowNum) -> new ContractSpec(
                rs.getLong("version"),
                ContractType.valueOf(rs.getString("contract_type")),
                rs.getString("settle_asset"),
                rs.getLong("notional_multiplier_units"),
                rs.getLong("price_tick_units"),
                rs.getLong("settle_scale_units"),
                rs.getLong("initial_margin_rate_ppm"),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm")), symbol, instrumentVersion).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("instrument contract spec not found for "
                        + symbol + " version " + instrumentVersion));
    }

    public InstrumentType instrumentType(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT instrument_type
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                """, (rs, rowNum) -> InstrumentType.valueOf(rs.getString("instrument_type")),
                symbol, instrumentVersion).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("instrument type not found for "
                        + symbol + " version " + instrumentVersion));
    }

    public long latestMarkPriceTicks(String symbol, long instrumentVersion) {
        MarkPriceEvent markPrice = latestMarkPrice(symbol);
        if (markPrice.instrumentVersion() != instrumentVersion) {
            throw new IllegalStateException("mark price instrument version mismatch for " + symbol
                    + ": expected=" + instrumentVersion + ", actual=" + markPrice.instrumentVersion());
        }
        return markPrice.markPriceTicks();
    }

    public long settlementMarkPriceTicks(String symbol,
                                         long instrumentVersion,
                                         Instant settlementTime,
                                         Duration priceWindow) {
        return latestMarkPriceTicks(symbol, instrumentVersion);
    }

    public long latestMarkPriceUnits(String symbol) {
        return latestMarkPrice(symbol).markPriceUnits();
    }

    public long settlementMarkPriceUnits(String symbol, Instant settlementTime, Duration priceWindow) {
        return latestMarkPriceUnits(symbol);
    }

    private MarkPriceEvent latestMarkPrice(String symbol) {
        if (markPriceCache == null) {
            throw new IllegalStateException("mark price cache is not configured");
        }
        return markPriceCache.requireFresh(symbol);
    }

    public SpotInstrumentSpec spotInstrumentSpec(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT version, base_asset, quote_asset, quantity_step_units, notional_multiplier_units
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                   AND instrument_type = 'SPOT'
                   AND contract_type = 'SPOT'
                """, (rs, rowNum) -> new SpotInstrumentSpec(
                rs.getLong("version"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getLong("quantity_step_units"),
                rs.getLong("notional_multiplier_units")), symbol, instrumentVersion).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("spot instrument spec not found for "
                        + symbol + " version " + instrumentVersion));
    }

    public Optional<LiquidationFeeContext> liquidationFeeContext(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT liquidation_order_id,
                       candidate_id,
                       liquidation_fee_rate_ppm
                  FROM liquidation_orders
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'FILLED')
                """, (rs, rowNum) -> new LiquidationFeeContext(
                rs.getLong("liquidation_order_id"),
                rs.getLong("candidate_id"),
                rs.getLong("liquidation_fee_rate_ppm")), orderId, userId, symbol).stream().findFirst();
    }

    public void completeTradeSide(ProductLine productLine,
                                  MatchTradeEvent trade,
                                  TradeParticipantRole role,
                                  String commandId,
                                  long orderMarginConsumedUnits,
                                  long orderMarginReleasedUnits,
                                  Instant now) {
        tradeSettlementSideRepository.complete(productLine, trade, role, commandId,
                orderMarginConsumedUnits, orderMarginReleasedUnits, now);
    }

    public void settleRealizedPnl(long userId,
                                  String asset,
                                  long orderId,
                                  long tradeId,
                                  String symbol,
                                  MarginMode marginMode,
                                  long realizedPnlDeltaUnits,
                                  Instant now) {
        settleRealizedPnl(AccountType.USDT_PERPETUAL, userId, asset, orderId, tradeId, symbol, marginMode,
                realizedPnlDeltaUnits, now);
    }

    public void settleRealizedPnl(AccountType accountType,
                                  long userId,
                                  String asset,
                                  long orderId,
                                  long tradeId,
                                  String symbol,
                                  MarginMode marginMode,
                                  long realizedPnlDeltaUnits,
                                  Instant now) {
        if (realizedPnlDeltaUnits == 0) {
            return;
        }
        AccountType normalizedType = requireAccountType(accountType);
        String referenceId = tradeId + ":" + orderId;
        if (isLegacyPerpetualAccount(normalizedType)) {
            Optional<Long> fastSettlement = trySettleLegacyAvailableBalanceAndLedger(
                    userId, asset, realizedPnlDeltaUnits, marginMode,
                    "TRADE_PNL", referenceId, "REALIZED_PNL",
                    null, null, null, null, now);
            if (fastSettlement.isPresent()) {
                return;
            }
            int ledgerRows = jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, created_at
                    ) VALUES (?, ?, ?, ?, 0, 'TRADE_PNL', ?, 'REALIZED_PNL', ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY),
                    userId, asset, realizedPnlDeltaUnits,
                    referenceId, Timestamp.from(now));
            requireSingleRow(ledgerRows, "trade pnl ledger insert");
            long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                    realizedPnlDeltaUnits, now);
            int ledgerRowsAfter = jdbcTemplate.update("""
                    UPDATE account_ledger_entries
                       SET balance_after_units = ?
                     WHERE reference_type = 'TRADE_PNL'
                       AND reference_id = ?
                       AND user_id = ?
                       AND asset = ?
                    """, balanceAfterUnits, referenceId, userId, asset);
            requireSingleRow(ledgerRowsAfter, "trade pnl ledger update");
            return;
        }
        insertProductSettlementLedger(userId, normalizedType, asset, realizedPnlDeltaUnits, 0L,
                "TRADE_PNL", referenceId, "REALIZED_PNL", now);
        long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                realizedPnlDeltaUnits, now);
        updateProductSettlementLedgerBalance(userId, normalizedType, asset, "TRADE_PNL", referenceId,
                balanceAfterUnits);
    }

    public boolean settleLifecyclePnl(AccountType accountType,
                                      long userId,
                                      String asset,
                                      String referenceType,
                                      String referenceId,
                                      String reason,
                                      String symbol,
                                      MarginMode marginMode,
                                      long realizedPnlDeltaUnits,
                                      Instant now) {
        if (realizedPnlDeltaUnits == 0) {
            return true;
        }
        AccountType normalizedType = requireAccountType(accountType);
        if (isLegacyPerpetualAccount(normalizedType)) {
            int ledgerRows = jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, symbol, created_at
                    ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY),
                    userId, asset, realizedPnlDeltaUnits,
                    referenceType, referenceId, reason, symbol, Timestamp.from(now));
            if (ledgerRows == 0) {
                return false;
            }
            long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                    realizedPnlDeltaUnits, now);
            int ledgerRowsAfter = jdbcTemplate.update("""
                    UPDATE account_ledger_entries
                       SET balance_after_units = ?
                     WHERE reference_type = ?
                       AND reference_id = ?
                       AND user_id = ?
                       AND asset = ?
                    """, balanceAfterUnits, referenceType, referenceId, userId, asset);
            requireSingleRow(ledgerRowsAfter, "lifecycle pnl ledger update");
            return true;
        }
        if (!tryInsertProductSettlementLedger(userId, normalizedType, asset, realizedPnlDeltaUnits, 0L,
                referenceType, referenceId, reason, now)) {
            return false;
        }
        long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                realizedPnlDeltaUnits, now);
        updateProductSettlementLedgerBalance(userId, normalizedType, asset, referenceType, referenceId,
                balanceAfterUnits);
        return true;
    }

    public OrderMarginApplication settleOptionPremium(AccountType accountType,
                                    OrderSide side,
                                    long userId,
                                    String asset,
                                    long orderId,
                                    long tradeId,
                                    String symbol,
                                    MarginMode marginMode,
                                    long premiumUnits,
                                    AccountType reservationAccountType,
                                    String reservationAsset,
                                    long reservedUnits,
                                    long orderQuantitySteps,
                                    long fillQuantitySteps,
                                    Instant now) {
        if (premiumUnits <= 0) {
            return OrderMarginApplication.NONE;
        }
        AccountType normalizedType = requireAccountType(accountType);
        if (normalizedType != AccountType.OPTION) {
            throw new IllegalArgumentException("option premium requires OPTION account");
        }
        String referenceId = tradeId + ":" + orderId + ":" + side.name();
        if (side == OrderSide.BUY) {
            if (reservationAccountType != AccountType.OPTION || !asset.equals(reservationAsset)
                    || reservedUnits < premiumUnits) {
                throw new IllegalStateException("option premium reservation snapshot is insufficient");
            }
            long allocatedUnits = Math.multiplyExact(reservedUnits, fillQuantitySteps) / orderQuantitySteps;
            if (premiumUnits > allocatedUnits) {
                throw new IllegalStateException("option premium exceeds allocated order reservation");
            }
            debitBalanceLock(normalizedType, userId, asset, premiumUnits, now);
            long releasedUnits = Math.max(0L, Math.subtractExact(allocatedUnits, premiumUnits));
            if (releasedUnits > 0L) {
                releaseBalanceLock(normalizedType, userId, asset, releasedUnits, now);
            }
            long balanceAfterUnits = productEquity(normalizedType, userId, asset);
            insertProductSettlementLedger(userId, normalizedType, asset, Math.negateExact(premiumUnits),
                    balanceAfterUnits, "OPTION_PREMIUM", referenceId, "OPTION_PREMIUM_PAID", now);
            return new OrderMarginApplication(premiumUnits, releasedUnits);
        }
        long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                premiumUnits, now);
        insertProductSettlementLedger(userId, normalizedType, asset, premiumUnits, balanceAfterUnits,
                "OPTION_PREMIUM", referenceId, "OPTION_PREMIUM_RECEIVED", now);
        return OrderMarginApplication.NONE;
    }

    public void settleRealizedPnl(long userId,
                                  String asset,
                                  long orderId,
                                  long tradeId,
                                  long realizedPnlDeltaUnits,
                                  Instant now) {
        settleRealizedPnl(userId, asset, orderId, tradeId, "", MarginMode.CROSS, realizedPnlDeltaUnits, now);
    }

    public void settleTradeFee(long userId,
                               String asset,
                               long orderId,
                               long tradeId,
                               long feeDeltaUnits,
                               String reason,
                               long feeRatePpm,
                               String symbol,
                               MarginMode marginMode,
                               Instant now) {
        settleTradeFee(AccountType.USDT_PERPETUAL, userId, asset, orderId, tradeId, feeDeltaUnits, reason,
                feeRatePpm, symbol, marginMode, now);
    }

    public void settleTradeFee(AccountType accountType,
                               long userId,
                               String asset,
                               long orderId,
                               long tradeId,
                               long feeDeltaUnits,
                               String reason,
                               long feeRatePpm,
                               String symbol,
                               MarginMode marginMode,
                               Instant now) {
        if (feeDeltaUnits == 0) {
            return;
        }
        AccountType normalizedType = requireAccountType(accountType);
        String referenceId = tradeId + ":" + orderId;
        if (isLegacyPerpetualAccount(normalizedType)) {
            Optional<Long> fastSettlement = trySettleLegacyAvailableBalanceAndLedger(
                    userId, asset, feeDeltaUnits, marginMode,
                    "TRADE_FEE", referenceId, reason,
                    tradeId, orderId, symbol, feeRatePpm, now);
            if (fastSettlement.isPresent()) {
                return;
            }
            int ledgerRows = jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, trade_id, order_id, symbol, fee_rate_ppm, created_at
                    ) VALUES (?, ?, ?, ?, 0, 'TRADE_FEE', ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY),
                    userId, asset, feeDeltaUnits, referenceId,
                    reason, tradeId, orderId, symbol, feeRatePpm, Timestamp.from(now));
            requireSingleRow(ledgerRows, "trade fee ledger insert");
            long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                    feeDeltaUnits, now);
            int ledgerRowsAfter = jdbcTemplate.update("""
                    UPDATE account_ledger_entries
                       SET balance_after_units = ?
                     WHERE reference_type = 'TRADE_FEE'
                       AND reference_id = ?
                       AND user_id = ?
                       AND asset = ?
                    """, balanceAfterUnits, referenceId, userId, asset);
            requireSingleRow(ledgerRowsAfter, "trade fee ledger update");
            return;
        }
        insertProductSettlementLedger(userId, normalizedType, asset, feeDeltaUnits, 0L,
                "TRADE_FEE", referenceId, reason, now);
        long balanceAfterUnits = applyAmountToBalance(normalizedType, userId, asset, symbol, marginMode,
                feeDeltaUnits, now);
        updateProductSettlementLedgerBalance(userId, normalizedType, asset, "TRADE_FEE", referenceId,
                balanceAfterUnits);
    }

    public void settleSpotTradeSide(long userId,
                                    long orderId,
                                    long tradeId,
                                    String symbol,
                                    OrderSide side,
                                    long priceTicks,
                                    long quantitySteps,
                                    SpotInstrumentSpec spec,
                                    long feeRatePpm,
                                    String feeReason,
                                    boolean orderCompleted,
                                    Instant now) {
        if (priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("priceTicks and quantitySteps must be positive");
        }
        SpotReservation reservation = lockSpotReservation(orderId, userId, symbol);
        if (reservation.side() != side) {
            throw new IllegalStateException("spot reservation side mismatch for order " + orderId);
        }
        long baseUnits = multiplyToLong(quantitySteps, spec.quantityStepUnits());
        long quoteUnits = multiplyToLong(priceTicks, quantitySteps, spec.notionalMultiplierUnits());
        long feeUnits = spotFeeUnits(quoteUnits, feeRatePpm);
        long positiveFeeUnits = feeRatePpm > 0 ? feeUnits : 0L;
        long settledUnits = side == OrderSide.BUY
                ? Math.addExact(quoteUnits, positiveFeeUnits)
                : baseUnits;
        long remainingReservationUnits = Math.subtractExact(reservation.reservedUnits(),
                Math.addExact(reservation.settledUnits(), reservation.releasedUnits()));
        if (settledUnits > remainingReservationUnits) {
            throw new IllegalStateException("spot reservation is smaller than filled amount for order " + orderId);
        }
        long releaseUnits = orderCompleted ? Math.subtractExact(remainingReservationUnits, settledUnits) : 0L;
        if (side == OrderSide.BUY) {
            debitSpotLocked(userId, spec.quoteAsset(), quoteUnits, now, tradeId, orderId, "SPOT_BUY_COST");
            if (positiveFeeUnits > 0) {
                debitSpotLocked(userId, spec.quoteAsset(), positiveFeeUnits, now, tradeId, orderId, feeReason);
            } else if (feeUnits > 0) {
                creditSpotAvailable(userId, spec.quoteAsset(), feeUnits, now, tradeId, orderId, feeReason);
            }
            creditSpotAvailable(userId, spec.baseAsset(), baseUnits, now, tradeId, orderId, "SPOT_BUY_FILL");
            releaseSpotLocked(userId, spec.quoteAsset(), releaseUnits, now);
        } else {
            debitSpotLocked(userId, spec.baseAsset(), baseUnits, now, tradeId, orderId, "SPOT_SELL_BASE");
            creditSpotAvailable(userId, spec.quoteAsset(), quoteUnits, now, tradeId, orderId, "SPOT_SELL_PROCEEDS");
            if (positiveFeeUnits > 0) {
                debitSpotAvailable(userId, spec.quoteAsset(), positiveFeeUnits, now, tradeId, orderId, feeReason);
            } else if (feeUnits > 0) {
                creditSpotAvailable(userId, spec.quoteAsset(), feeUnits, now, tradeId, orderId, feeReason);
            }
            releaseSpotLocked(userId, spec.baseAsset(), releaseUnits, now);
        }
        updateSpotReservation(orderId, settledUnits, releaseUnits, feeReason, now);
    }

    public void settleTradeFee(long userId,
                               String asset,
                               long orderId,
                               long tradeId,
                               long feeDeltaUnits,
                               String reason,
                               long feeRatePpm,
                               String symbol,
                               Instant now) {
        settleTradeFee(userId, asset, orderId, tradeId, feeDeltaUnits, reason, feeRatePpm, symbol, MarginMode.CROSS,
                now);
    }

    @Transactional
    public Optional<LiquidationFeeSettlement> settleLiquidationFee(long userId,
                                                                   String asset,
                                                                   long orderId,
                                                                   long tradeId,
                                                                   String symbol,
                                                                   MarginMode marginMode,
                                                                   long requestedFeeUnits,
                                                                   LiquidationFeeContext context,
                                                                   Instant now) {
        return settleLiquidationFee(AccountType.USDT_PERPETUAL, userId, asset, orderId, tradeId, symbol,
                marginMode, requestedFeeUnits, context, now);
    }

    @Transactional
    public Optional<LiquidationFeeSettlement> settleLiquidationFee(AccountType accountType,
                                                                   long userId,
                                                                   String asset,
                                                                   long orderId,
                                                                   long tradeId,
                                                                   String symbol,
                                                                   MarginMode marginMode,
                                                                   long requestedFeeUnits,
                                                                   LiquidationFeeContext context,
                                                                   Instant now) {
        if (requestedFeeUnits <= 0 || context == null || context.feeRatePpm() <= 0) {
            return Optional.empty();
        }
        AccountType normalizedType = requireAccountType(accountType);
        String referenceId = tradeId + ":" + orderId;
        if (liquidationFeeReferenceExists(normalizedType, userId, asset, referenceId)) {
            return Optional.empty();
        }
        BalanceDebitResult debit = applyCappedDebitToBalance(normalizedType, userId, asset, symbol, marginMode,
                requestedFeeUnits, now);
        if (debit.debitedUnits() <= 0) {
            return Optional.empty();
        }
        if (isLegacyPerpetualAccount(normalizedType)) {
            int ledgerRows = jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, trade_id, order_id, symbol, fee_rate_ppm, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'LIQUIDATION_FEE', ?, 'COLLECT_LIQUIDATION_FEE', ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY), userId, asset,
                    Math.negateExact(debit.debitedUnits()), debit.balanceAfterUnits(), referenceId, tradeId,
                    orderId, symbol, context.feeRatePpm(), Timestamp.from(now));
            requireSingleRow(ledgerRows, "liquidation fee ledger insert");
        } else {
            insertProductSettlementLedger(userId, normalizedType, asset, Math.negateExact(debit.debitedUnits()),
                    debit.balanceAfterUnits(), "LIQUIDATION_FEE", referenceId, "COLLECT_LIQUIDATION_FEE", now);
        }
        return Optional.of(new LiquidationFeeSettlement(context.liquidationOrderId(), context.candidateId(),
                debit.debitedUnits(), context.feeRatePpm()));
    }

    public OrderMarginApplication applyOrderMargin(ProductLine productLine,
                                                   long orderId,
                                                   AccountType accountType,
                                                   long userId,
                                                   String symbol,
                                                   MarginMode marginMode,
                                                   PositionSide positionSide,
                                                   String asset,
                                                   long reservedUnits,
                                                   long orderQuantitySteps,
                                                   long fillQuantitySteps,
                                                   long openSteps,
                                                   long actualMarginUnits,
                                                   boolean reduceOnly,
                                                   Instant now) {
        if (reservedUnits == 0L) {
            if (openSteps > 0L) {
                throw new IllegalStateException("opening fill requires an order margin reservation snapshot");
            }
            return OrderMarginApplication.NONE;
        }
        ProductLine resolvedProductLine = productLine(productLine);
        if (accountType == null || accountType.productLine().orElse(null) != resolvedProductLine
                || asset == null || asset.isBlank()) {
            throw new IllegalStateException("order margin reservation scope does not match fill");
        }
        if (reduceOnly && openSteps > 0L) {
            throw new IllegalStateException("reduce-only order cannot consume opening margin");
        }
        long allocatedUnits = Math.multiplyExact(reservedUnits, fillQuantitySteps) / orderQuantitySteps;
        if (actualMarginUnits > allocatedUnits) {
            throw new IllegalStateException("opening margin exceeds allocated order reservation");
        }
        long releasedUnits = Math.max(0L, Math.subtractExact(allocatedUnits, actualMarginUnits));
        if (actualMarginUnits > 0L) {
            MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
            PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
            int positionMarginRows = positionMarginRepository.add(
                    resolvedProductLine, userId, symbol, asset, normalizedMarginMode,
                    normalizedPositionSide, actualMarginUnits, now);
            requireSingleRow(positionMarginRows, "position margin upsert");
            schedulePositionCacheProjection(resolvedProductLine, userId, symbol,
                    normalizedMarginMode, normalizedPositionSide);
        }
        if (releasedUnits > 0L) {
            releaseBalanceLock(accountType, userId, asset, releasedUnits, now);
        }
        return new OrderMarginApplication(actualMarginUnits, releasedUnits);
    }

    public void releasePositionMargin(long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      long closeSteps,
                                      long positionAbsSteps,
                                      Instant now) {
        releasePositionMargin(userId, symbol, marginMode, closeSteps, PositionSide.NET, positionAbsSteps, now);
    }

    public void releasePositionMargin(long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      long closeSteps,
                                      PositionSide positionSide,
                                      long positionAbsSteps,
                                      Instant now) {
        releasePositionMargin(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, closeSteps, positionSide,
                positionAbsSteps, now);
    }

    public void releasePositionMargin(ProductLine productLine,
                                      long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      long closeSteps,
                                      PositionSide positionSide,
                                      long positionAbsSteps,
                                      Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        AccountType accountType = accountType(resolvedProductLine);
        List<PositionMargin> margins = positionMarginRepository
                .lockByPosition(
                        resolvedProductLine, userId, symbol, normalizedMarginMode, normalizedPositionSide)
                .stream()
                .map(row -> new PositionMargin(
                        row.symbol(), row.asset(), row.marginMode(), row.positionSide(),
                        row.marginUnits(), accountType))
                .toList();
        for (PositionMargin margin : margins) {
            long amountUnits = MarginTransferMath.positionMarginReleaseAmount(margin.marginUnits(),
                    closeSteps, positionAbsSteps);
            if (amountUnits <= 0) {
                continue;
            }
            releaseBalanceLock(margin.accountType(), userId, margin.asset(), amountUnits, now);
            int marginRows = positionMarginRepository.subtract(
                    resolvedProductLine, userId, symbol, margin.asset(), margin.marginMode(),
                    margin.positionSide(), amountUnits, now);
            requireSingleRow(marginRows, "position margin release");
            positionMarginRepository.deleteZero(
                    resolvedProductLine, userId, symbol, margin.asset(),
                    margin.marginMode(), margin.positionSide());
            schedulePositionCacheProjection(resolvedProductLine, userId, symbol,
                    margin.marginMode(), margin.positionSide());
        }
    }

    private void releaseBalanceLock(AccountType accountType, long userId, String asset, long amountUnits, Instant now) {
        if (isLegacyPerpetualAccount(accountType)) {
            releaseLegacyBalanceLock(userId, asset, amountUnits, now);
            return;
        }
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked product balance for margin release");
        }
    }

    private void debitBalanceLock(AccountType accountType, long userId, String asset, long amountUnits, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked product balance for option premium");
        }
    }

    private long productEquity(AccountType accountType, long userId, String asset) {
        Long equityUnits = jdbcTemplate.queryForObject("""
                SELECT b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units
                  FROM account_product_balances b
             LEFT JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ?
                   AND b.user_id = ?
                   AND b.asset = ?
                """, Long.class, accountType.name(), userId, asset);
        return equityUnits == null ? 0L : equityUnits;
    }

    private void releaseLegacyBalanceLock(long userId, String asset, long amountUnits, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked balance for margin release");
        }
    }

    private long applyAmountToBalance(AccountType accountType,
                                      long userId,
                                      String asset,
                                      String symbol,
                                      MarginMode marginMode,
                                      long amountUnits,
                                      Instant now) {
        if (isLegacyPerpetualAccount(accountType)) {
            return applyAmountToLegacyBalance(userId, asset, symbol, marginMode, amountUnits, now);
        }
        return applyAmountToProductBalance(accountType, userId, asset, symbol, marginMode, amountUnits, now);
    }

    private long applyAmountToLegacyBalance(long userId,
                                            String asset,
                                            String symbol,
                                            MarginMode marginMode,
                                            long amountUnits,
                                            Instant now) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        Optional<Long> availableDebitFastPath = tryApplyLegacyAvailableDebitFastPath(
                userId, asset, normalizedMarginMode, amountUnits, now);
        if (availableDebitFastPath.isPresent()) {
            return availableDebitFastPath.get();
        }
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        availableDebitFastPath = tryApplyLegacyAvailableDebitFastPath(
                userId, asset, normalizedMarginMode, amountUnits, now);
        if (availableDebitFastPath.isPresent()) {
            return availableDebitFastPath.get();
        }
        List<PositionMargin> lockedMargins = amountUnits < 0
                ? lockPositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, symbol, normalizedMarginMode)
                : List.of();
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units"),
                rs.getLong("reserved_units")), userId, asset);
        long availableInput = amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED
                ? 0L
                : current.availableUnits();
        long settlementDeficit = amountUnits > 0
                ? Math.subtractExact(current.deficitUnits(), current.reservedDeficitUnits())
                : current.deficitUnits();
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                settlementDeficit, amountUnits, maxLockedDebitUnits);
        next = new BalanceSettlementState(next.availableUnits(), next.lockedUnits(),
                Math.addExact(next.deficitUnits(), amountUnits > 0 ? current.reservedDeficitUnits() : 0L),
                current.reservedDeficitUnits());
        if (amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits(),
                    current.reservedDeficitUnits());
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "pnl balance update");
        updateDeficitIfChanged(userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "pnl deficit update");
        return PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits());
    }

    private long applyAmountToProductBalance(AccountType accountType,
                                             long userId,
                                             String asset,
                                             String symbol,
                                             MarginMode marginMode,
                                             long amountUnits,
                                             Instant now) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        Optional<Long> availableDebitFastPath = tryApplyProductAvailableDebitFastPath(
                accountType, userId, asset, normalizedMarginMode, amountUnits, now);
        if (availableDebitFastPath.isPresent()) {
            return availableDebitFastPath.get();
        }
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_product_deficits (account_type, user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, ?, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        availableDebitFastPath = tryApplyProductAvailableDebitFastPath(
                accountType, userId, asset, normalizedMarginMode, amountUnits, now);
        if (availableDebitFastPath.isPresent()) {
            return availableDebitFastPath.get();
        }
        ProductLine resolvedProductLine = accountType.productLine().orElse(ProductLine.LINEAR_PERPETUAL);
        List<PositionMargin> lockedMargins = amountUnits < 0
                ? lockPositionMargins(resolvedProductLine, userId, asset, symbol, normalizedMarginMode)
                : List.of();
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_product_balances b
                  JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ? AND b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units"),
                rs.getLong("reserved_units")), accountType.name(), userId, asset);
        long availableInput = amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED
                ? 0L
                : current.availableUnits();
        long settlementDeficit = amountUnits > 0
                ? Math.subtractExact(current.deficitUnits(), current.reservedDeficitUnits())
                : current.deficitUnits();
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                settlementDeficit, amountUnits, maxLockedDebitUnits);
        next = new BalanceSettlementState(next.availableUnits(), next.lockedUnits(),
                Math.addExact(next.deficitUnits(), amountUnits > 0 ? current.reservedDeficitUnits() : 0L),
                current.reservedDeficitUnits());
        if (amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits(),
                    current.reservedDeficitUnits());
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(resolvedProductLine, userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), accountType.name(), userId,
                asset);
        requireSingleRow(balanceRows, "product pnl balance update");
        updateProductDeficitIfChanged(accountType, userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "product pnl deficit update");
        return PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits());
    }

    private BalanceDebitResult applyCappedDebitToBalance(AccountType accountType,
                                                         long userId,
                                                         String asset,
                                                         String symbol,
                                                         MarginMode marginMode,
                                                         long requestedDebitUnits,
                                                         Instant now) {
        if (isLegacyPerpetualAccount(accountType)) {
            return applyCappedDebitToLegacyBalance(userId, asset, symbol, marginMode, requestedDebitUnits, now);
        }
        return applyCappedDebitToProductBalance(accountType, userId, asset, symbol, marginMode, requestedDebitUnits,
                now);
    }

    private BalanceDebitResult applyCappedDebitToLegacyBalance(long userId,
                                                               String asset,
                                                               String symbol,
                                                               MarginMode marginMode,
                                                               long requestedDebitUnits,
                                                               Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        List<PositionMargin> lockedMargins = lockPositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, symbol,
                normalizedMarginMode);
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units"),
                rs.getLong("reserved_units")), userId, asset);
        long availableInput = normalizedMarginMode == MarginMode.ISOLATED ? 0L : current.availableUnits();
        long collectibleUnits = Math.min(requestedDebitUnits, Math.addExact(availableInput, maxLockedDebitUnits));
        if (collectibleUnits <= 0) {
            return new BalanceDebitResult(0L, PnlSettlementMath.netEquityUnits(current.availableUnits(),
                    current.lockedUnits(), current.deficitUnits()));
        }
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                current.deficitUnits(), Math.negateExact(collectibleUnits), maxLockedDebitUnits);
        next = new BalanceSettlementState(next.availableUnits(), next.lockedUnits(), next.deficitUnits(),
                current.reservedDeficitUnits());
        if (normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits(),
                    current.reservedDeficitUnits());
        }
        if (next.deficitUnits() != current.deficitUnits()) {
            throw new IllegalStateException("liquidation fee must not create account deficit");
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "liquidation fee balance update");
        updateDeficitIfChanged(userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "liquidation fee deficit update");
        return new BalanceDebitResult(collectibleUnits,
                PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits()));
    }

    private BalanceDebitResult applyCappedDebitToProductBalance(AccountType accountType,
                                                                long userId,
                                                                String asset,
                                                                String symbol,
                                                                MarginMode marginMode,
                                                                long requestedDebitUnits,
                                                                Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_product_deficits (account_type, user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, ?, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        ProductLine resolvedProductLine = accountType.productLine().orElse(ProductLine.LINEAR_PERPETUAL);
        List<PositionMargin> lockedMargins = lockPositionMargins(resolvedProductLine, userId, asset, symbol,
                normalizedMarginMode);
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_product_balances b
                  JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ? AND b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
        """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units"),
                rs.getLong("reserved_units")), accountType.name(), userId, asset);
        long availableInput = normalizedMarginMode == MarginMode.ISOLATED ? 0L : current.availableUnits();
        long collectibleUnits = Math.min(requestedDebitUnits, Math.addExact(availableInput, maxLockedDebitUnits));
        if (collectibleUnits <= 0) {
            return new BalanceDebitResult(0L, PnlSettlementMath.netEquityUnits(current.availableUnits(),
                    current.lockedUnits(), current.deficitUnits()));
        }
        BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits(),
                current.deficitUnits(), Math.negateExact(collectibleUnits), maxLockedDebitUnits);
        next = new BalanceSettlementState(next.availableUnits(), next.lockedUnits(), next.deficitUnits(),
                current.reservedDeficitUnits());
        if (normalizedMarginMode == MarginMode.ISOLATED) {
            next = new BalanceSettlementState(current.availableUnits(), next.lockedUnits(), next.deficitUnits(),
                    current.reservedDeficitUnits());
        }
        if (next.deficitUnits() != current.deficitUnits()) {
            throw new IllegalStateException("liquidation fee must not create account deficit");
        }
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(resolvedProductLine, userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), accountType.name(), userId,
                asset);
        requireSingleRow(balanceRows, "product liquidation fee balance update");
        updateProductDeficitIfChanged(accountType, userId, asset, current.deficitUnits(), next.deficitUnits(), now,
                "product liquidation fee deficit update");
        return new BalanceDebitResult(collectibleUnits,
                PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits()));
    }

    private Optional<Long> tryApplyLegacyAvailableDebitFastPath(long userId,
                                                                String asset,
                                                                MarginMode marginMode,
                                                                long amountUnits,
                                                                Instant now) {
        if (amountUnits >= 0 || marginMode != MarginMode.CROSS) {
            return Optional.empty();
        }
        List<Long> rows = jdbcTemplate.query("""
                UPDATE account_balances b
                   SET available_units = b.available_units + ?,
                       updated_at = ?
                 WHERE b.user_id = ?
                   AND b.asset = ?
                   AND b.available_units + ? >= 0
                 RETURNING b.available_units + b.locked_units - COALESCE((
                       SELECT d.deficit_units
                         FROM account_deficits d
                        WHERE d.user_id = b.user_id
                          AND d.asset = b.asset
                   ), 0) AS balance_after_units
                """, (rs, rowNum) -> rs.getLong("balance_after_units"),
                amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        return rows == null ? Optional.empty() : rows.stream().findFirst();
    }

    /**
     * Collapses the overwhelmingly common perpetual cashflow path into one database round trip:
     * update available balance and append the immutable ledger row with its final balance.
     *
     * <p>Negative cross-margin cashflows use this path only while available balance is sufficient.
     * Positive cashflows use it only when there is no unsettled deficit. All other cases return empty
     * without changing state and continue through the full locked-margin/deficit settlement algorithm.
     */
    private Optional<Long> trySettleLegacyAvailableBalanceAndLedger(long userId,
                                                                    String asset,
                                                                    long amountUnits,
                                                                    MarginMode marginMode,
                                                                    String referenceType,
                                                                    String referenceId,
                                                                    String reason,
                                                                    Long tradeId,
                                                                    Long orderId,
                                                                    String symbol,
                                                                    Long feeRatePpm,
                                                                    Instant now) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        long entryId = sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY);
        Timestamp timestamp = Timestamp.from(now);
        List<Long> rows = jdbcTemplate.query("""
                WITH updated_balance AS (
                    UPDATE account_balances b
                       SET available_units = b.available_units + ?,
                           updated_at = ?
                     WHERE b.user_id = ?
                       AND b.asset = ?
                       AND NOT EXISTS (
                           SELECT 1
                             FROM account_ledger_entries l
                            WHERE l.reference_type = ?
                              AND l.reference_id = ?
                              AND l.user_id = ?
                              AND l.asset = ?
                       )
                       AND (
                           (? < 0 AND ? = 'CROSS' AND b.available_units + ? >= 0)
                           OR
                           (? > 0 AND COALESCE((
                               SELECT d.deficit_units - d.reserved_units
                                 FROM account_deficits d
                                WHERE d.user_id = b.user_id
                                  AND d.asset = b.asset
                           ), 0) = 0)
                       )
                 RETURNING b.available_units + b.locked_units - COALESCE((
                           SELECT d.deficit_units
                             FROM account_deficits d
                            WHERE d.user_id = b.user_id
                              AND d.asset = b.asset
                       ), 0) AS balance_after_units
                ),
                inserted_ledger AS (
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, trade_id, order_id, symbol,
                        fee_rate_ppm, created_at
                    )
                    SELECT ?, ?, ?, ?, balance_after_units,
                           ?, ?, ?, CAST(? AS BIGINT), CAST(? AS BIGINT), CAST(? AS TEXT),
                           CAST(? AS BIGINT), ?
                      FROM updated_balance
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    RETURNING balance_after_units
                )
                SELECT balance_after_units
                  FROM inserted_ledger
                """, (rs, rowNum) -> rs.getLong("balance_after_units"),
                amountUnits, timestamp, userId, asset,
                referenceType, referenceId, userId, asset,
                amountUnits, normalizedMarginMode.name(), amountUnits, amountUnits,
                entryId, userId, asset, amountUnits,
                referenceType, referenceId, reason, tradeId, orderId, symbol, feeRatePpm, timestamp);
        return rows == null ? Optional.empty() : rows.stream().findFirst();
    }

    private Optional<Long> tryApplyProductAvailableDebitFastPath(AccountType accountType,
                                                                 long userId,
                                                                 String asset,
                                                                 MarginMode marginMode,
                                                                 long amountUnits,
                                                                 Instant now) {
        if (amountUnits >= 0 || marginMode != MarginMode.CROSS) {
            return Optional.empty();
        }
        List<Long> rows = jdbcTemplate.query("""
                UPDATE account_product_balances b
                   SET available_units = b.available_units + ?,
                       updated_at = ?
                 WHERE b.account_type = ?
                   AND b.user_id = ?
                   AND b.asset = ?
                   AND b.available_units + ? >= 0
                RETURNING b.available_units + b.locked_units - COALESCE((
                       SELECT d.deficit_units
                         FROM account_product_deficits d
                        WHERE d.account_type = b.account_type
                          AND d.user_id = b.user_id
                          AND d.asset = b.asset
                   ), 0) AS balance_after_units
                """, (rs, rowNum) -> rs.getLong("balance_after_units"),
                amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits);
        return rows == null ? Optional.empty() : rows.stream().findFirst();
    }

    private void updateDeficitIfChanged(long userId,
                                        String asset,
                                        long currentDeficitUnits,
                                        long nextDeficitUnits,
                                        Instant now,
                                        String operation) {
        if (currentDeficitUnits == nextDeficitUnits) {
            return;
        }
        int deficitRows = jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, nextDeficitUnits, Timestamp.from(now), userId, asset);
        requireSingleRow(deficitRows, operation);
    }

    private void updateProductDeficitIfChanged(AccountType accountType,
                                               long userId,
                                               String asset,
                                               long currentDeficitUnits,
                                               long nextDeficitUnits,
                                               Instant now,
                                               String operation) {
        if (currentDeficitUnits == nextDeficitUnits) {
            return;
        }
        int deficitRows = jdbcTemplate.update("""
                UPDATE account_product_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE account_type = ?
                   AND user_id = ?
                   AND asset = ?
                """, nextDeficitUnits, Timestamp.from(now), accountType.name(), userId, asset);
        requireSingleRow(deficitRows, operation);
    }

    private boolean liquidationFeeReferenceExists(AccountType accountType, long userId, String asset,
                                                  String referenceId) {
        if (!isLegacyPerpetualAccount(accountType)) {
            return jdbcTemplate.query("""
                    SELECT 1
                      FROM account_product_ledger_entries
                     WHERE reference_type = 'LIQUIDATION_FEE'
                       AND reference_id = ?
                     AND user_id = ?
                     AND account_type = ?
                     AND asset = ?
                    """, (rs, rowNum) -> 1, referenceId, userId, accountType.name(), asset)
                    .stream().findFirst().isPresent();
        }
        return jdbcTemplate.query("""
                SELECT 1
                  FROM account_ledger_entries
                 WHERE reference_type = 'LIQUIDATION_FEE'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, (rs, rowNum) -> 1, referenceId, userId, asset).stream().findFirst().isPresent();
    }

    private void insertProductSettlementLedger(long userId,
                                               AccountType accountType,
                                               String asset,
                                               long amountUnits,
                                               long balanceAfterUnits,
                                               String referenceType,
                                               String referenceId,
                                               String reason,
                                               Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.PRODUCT_LEDGER_ENTRY),
                userId, accountType.name(), asset,
                amountUnits, balanceAfterUnits, referenceType, referenceId, reason, Timestamp.from(now));
        requireSingleRow(rows, referenceType.toLowerCase().replace('_', ' ') + " product ledger insert");
    }

    private boolean tryInsertProductSettlementLedger(long userId,
                                                     AccountType accountType,
                                                     String asset,
                                                     long amountUnits,
                                                     long balanceAfterUnits,
                                                     String referenceType,
                                                     String referenceId,
                                                     String reason,
                                                     Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.PRODUCT_LEDGER_ENTRY),
                userId, accountType.name(), asset,
                amountUnits, balanceAfterUnits, referenceType, referenceId, reason, Timestamp.from(now));
        return rows == 1;
    }

    private void updateProductSettlementLedgerBalance(long userId,
                                                      AccountType accountType,
                                                      String asset,
                                                      String referenceType,
                                                      String referenceId,
                                                      long balanceAfterUnits) {
        int rows = jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = ?
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceType, referenceId, userId, accountType.name(), asset);
        requireSingleRow(rows, referenceType.toLowerCase().replace('_', ' ') + " product ledger update");
    }

    private List<PositionMargin> lockPositionMargins(long userId, String asset, String symbol, MarginMode marginMode) {
        return lockPositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, symbol, marginMode);
    }

    private List<PositionMargin> lockPositionMargins(ProductLine productLine,
                                                     long userId,
                                                     String asset,
                                                     String symbol,
                                                     MarginMode marginMode) {
        ProductLine resolvedProductLine = productLine(productLine);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        AccountType accountType = accountType(resolvedProductLine);
        return positionMarginRepository
                .lockByAsset(resolvedProductLine, userId, asset, symbol, normalizedMarginMode)
                .stream()
                .map(row -> new PositionMargin(
                        row.symbol(), row.asset(), row.marginMode(), row.positionSide(),
                        row.marginUnits(), accountType))
                .toList();
    }

    private void reducePositionMargins(long userId,
                                       String asset,
                                       long amountUnits,
                                       List<PositionMargin> lockedMargins,
                                       Instant now) {
        reducePositionMargins(ProductLine.LINEAR_PERPETUAL, userId, asset, amountUnits, lockedMargins, now);
    }

    private void reducePositionMargins(ProductLine productLine,
                                       long userId,
                                       String asset,
                                       long amountUnits,
                                       List<PositionMargin> lockedMargins,
                                       Instant now) {
        ProductLine resolvedProductLine = productLine(productLine);
        long remaining = amountUnits;
        for (PositionMargin margin : lockedMargins) {
            if (remaining <= 0) {
                break;
            }
            long debit = Math.min(margin.marginUnits(), remaining);
            int rows = positionMarginRepository.subtract(
                    resolvedProductLine, userId, margin.symbol(), asset,
                    margin.marginMode(), margin.positionSide(), debit, now);
            if (rows != 1) {
                throw new IllegalStateException("failed to reduce consumed position margin");
            }
            positionMarginRepository.deleteZero(
                    resolvedProductLine, userId, margin.symbol(), asset,
                    margin.marginMode(), margin.positionSide());
            schedulePositionCacheProjection(resolvedProductLine, userId, margin.symbol(),
                    margin.marginMode(), margin.positionSide());
            remaining = Math.subtractExact(remaining, debit);
        }
        if (remaining != 0) {
            throw new IllegalStateException("insufficient position margin for locked debit");
        }
    }

    private void schedulePositionCacheProjection(ProductLine productLine,
                                                 long userId,
                                                 String symbol,
                                                 MarginMode marginMode,
                                                 PositionSide positionSide) {
        if (positionCacheSynchronizer != null) {
            positionCacheSynchronizer.schedule(productLine, userId, symbol, marginMode, positionSide);
        }
    }

    private SpotReservation lockSpotReservation(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT user_id, side, asset, reserved_units, settled_units, released_units
                  FROM account_spot_order_reservations
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND status NOT IN ('RELEASED', 'SETTLED')
                 FOR UPDATE
                """, (rs, rowNum) -> new SpotReservation(
                rs.getLong("user_id"),
                OrderSide.valueOf(rs.getString("side")),
                rs.getString("asset"),
                rs.getLong("reserved_units"),
                rs.getLong("settled_units"),
                rs.getLong("released_units")), orderId, userId, symbol).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("active spot reservation not found for order " + orderId));
    }

    private void debitSpotLocked(long userId,
                                 String asset,
                                 long amountUnits,
                                 Instant now,
                                 long tradeId,
                                 long orderId,
                                 String reason) {
        if (amountUnits <= 0) {
            return;
        }
        ensureSpotBalance(userId, asset, now);
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked spot balance for order " + orderId);
        }
        insertSpotLedger(userId, asset, Math.negateExact(amountUnits), spotEquity(userId, asset),
                tradeId, orderId, reason, now);
    }

    private void releaseSpotLocked(long userId, String asset, long amountUnits, Instant now) {
        if (amountUnits <= 0) {
            return;
        }
        ensureSpotBalance(userId, asset, now);
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked spot balance for release");
        }
    }

    private void creditSpotAvailable(long userId,
                                     String asset,
                                     long amountUnits,
                                     Instant now,
                                     long tradeId,
                                     long orderId,
                                     String reason) {
        if (amountUnits <= 0) {
            return;
        }
        ensureSpotBalance(userId, asset, now);
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units + ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                """, amountUnits, Timestamp.from(now), userId, asset);
        requireSingleRow(rows, "spot available credit");
        insertSpotLedger(userId, asset, amountUnits, spotEquity(userId, asset),
                tradeId, orderId, reason, now);
    }

    private void debitSpotAvailable(long userId,
                                    String asset,
                                    long amountUnits,
                                    Instant now,
                                    long tradeId,
                                    long orderId,
                                    String reason) {
        if (amountUnits <= 0) {
            return;
        }
        ensureSpotBalance(userId, asset, now);
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units - ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                   AND available_units >= ?
                """, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient available spot balance for fee");
        }
        insertSpotLedger(userId, asset, Math.negateExact(amountUnits), spotEquity(userId, asset),
                tradeId, orderId, reason, now);
    }

    private void ensureSpotBalance(long userId, String asset, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES ('SPOT', ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
    }

    private long spotEquity(long userId, String asset) {
        Long equityUnits = jdbcTemplate.queryForObject("""
                SELECT available_units + locked_units
                  FROM account_product_balances
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                """, Long.class, userId, asset);
        return equityUnits == null ? 0L : equityUnits;
    }

    private void insertSpotLedger(long userId,
                                  String asset,
                                  long amountUnits,
                                  long balanceAfterUnits,
                                  long tradeId,
                                  long orderId,
                                  String reason,
                                  Instant now) {
        if (amountUnits == 0) {
            return;
        }
        String referenceId = tradeId + ":" + orderId + ":" + reason;
        int rows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, 'SPOT', ?, ?, ?, 'SPOT_TRADE', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.PRODUCT_LEDGER_ENTRY),
                userId, asset, amountUnits,
                balanceAfterUnits, referenceId, reason, Timestamp.from(now));
        requireSingleRow(rows, "spot trade ledger insert");
    }

    private void updateSpotReservation(long orderId,
                                       long settledUnits,
                                       long releasedUnits,
                                       String reason,
                                       Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_spot_order_reservations
                   SET settled_units = settled_units + ?,
                       released_units = released_units + ?,
                       status = CASE
                           WHEN settled_units + released_units + ? + ? >= reserved_units THEN 'SETTLED'
                           WHEN settled_units + ? > 0 THEN 'PARTIALLY_SETTLED'
                           WHEN released_units + ? > 0 THEN 'PARTIALLY_RELEASED'
                           ELSE status
                       END,
                       reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                   AND settled_units + released_units + ? + ? <= reserved_units
                """, settledUnits, releasedUnits, settledUnits, releasedUnits, settledUnits, releasedUnits,
                reason, Timestamp.from(now), orderId, settledUnits, releasedUnits);
        requireSingleRow(rows, "spot reservation settlement update");
    }

    private long spotFeeUnits(long quoteUnits, long feeRatePpm) {
        if (feeRatePpm == 0) {
            return 0L;
        }
        BigInteger numerator = BigInteger.valueOf(quoteUnits)
                .multiply(BigInteger.valueOf(Math.absExact(feeRatePpm)));
        return divideCeiling(numerator, BigInteger.valueOf(PPM));
    }

    private long multiplyToLong(long... values) {
        BigInteger product = BigInteger.ONE;
        for (long value : values) {
            if (value <= 0) {
                throw new IllegalArgumentException("spot settlement inputs must be positive");
            }
            product = product.multiply(BigInteger.valueOf(value));
        }
        return product.longValueExact();
    }

    private long divideCeiling(BigInteger numerator, BigInteger denominator) {
        if (numerator.signum() < 0 || denominator.signum() <= 0) {
            throw new IllegalArgumentException("positive numerator and denominator are required");
        }
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        return (quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE)).longValueExact();
    }

    private ProductBalanceResponse toProductBalance(AccountType accountType, BalanceResponse balance) {
        return new ProductBalanceResponse(balance.userId(), accountType, balance.asset(), balance.availableUnits(),
                balance.lockedUnits(), balance.equityUnits(), balance.updatedAt());
    }

    private BalanceResponse toBalance(AccountBalanceRepository.BalanceRow row, long deficitUnits) {
        long equityUnits = Math.subtractExact(
                Math.addExact(row.availableUnits(), row.lockedUnits()), deficitUnits);
        return new BalanceResponse(row.userId(), row.asset(), row.availableUnits(), row.lockedUnits(),
                equityUnits, row.updatedAt());
    }

    private ProductBalanceResponse toProductBalance(ProductBalanceRepository.ProductBalanceRow row,
                                                    long deficitUnits) {
        long equityUnits = Math.subtractExact(
                Math.addExact(row.availableUnits(), row.lockedUnits()), deficitUnits);
        return new ProductBalanceResponse(row.userId(), row.accountType(), row.asset(), row.availableUnits(),
                row.lockedUnits(), equityUnits, row.updatedAt());
    }

    private AccountType requireAccountType(AccountType accountType) {
        if (accountType == null) {
            throw new IllegalArgumentException("accountType is required");
        }
        return accountType;
    }

    private AccountType accountTypeFromNullableDbValue(String accountType) {
        if (accountType == null || accountType.isBlank()) {
            return AccountType.USDT_PERPETUAL;
        }
        return AccountType.valueOf(accountType);
    }

    private boolean isLegacyPerpetualAccount(AccountType accountType) {
        return accountType == AccountType.USDT_PERPETUAL;
    }

    public record OrderMarginApplication(long consumedUnits, long releasedUnits) {
        public static final OrderMarginApplication NONE = new OrderMarginApplication(0L, 0L);
    }

    public record OpenInterestLockRequest(ProductLine productLine, long userId, String symbol) {
        public OpenInterestLockRequest {
            if (productLine == null || userId <= 0L || symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("invalid open interest lock request");
            }
        }
    }

    private record PositionMargin(String symbol,
                                  String asset,
                                  MarginMode marginMode,
                                  PositionSide positionSide,
                                  long marginUnits,
                                  AccountType accountType) {
        private PositionMargin(String symbol, String asset, MarginMode marginMode, long marginUnits) {
            this(symbol, asset, marginMode, PositionSide.NET, marginUnits, AccountType.USDT_PERPETUAL);
        }
    }

    private record SpotReservation(
            long userId,
            OrderSide side,
            String asset,
            long reservedUnits,
            long settledUnits,
            long releasedUnits) {
    }

    private record ProductBalanceKey(AccountType accountType, String asset) {
    }

    private record PositionCollateralTarget(
            long userId,
            String symbol,
            String asset,
            PositionSide positionSide,
            long instrumentVersion,
            long signedQuantitySteps) {
    }

    private record RiskRemovalSnapshot(
            long instrumentVersion,
            long signedQuantitySteps,
            long unrealizedPnlUnits,
            long maintenanceMarginUnits,
            String status,
            Instant eventTime) {
    }

    private record PositionMarginAdjustmentReference(
            String asset,
            long amountUnits,
            String reason,
            String symbol) {
    }

    private Long nullableVersion(long version) {
        return version <= 0 ? null : version;
    }

    private long longOrZero(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    private AccountType accountType(ProductLine productLine) {
        return AccountType.valueOf(productLine(productLine).accountTypeCode());
    }

    private static String productLineExpression(String instrumentAlias) {
        return ProductLineSql.contractTypeProductLineCase(instrumentAlias + ".contract_type");
    }

    private static String accountTypeExpression(String instrumentAlias) {
        return ProductLineSql.contractTypeAccountTypeCase(instrumentAlias + ".contract_type");
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

}
