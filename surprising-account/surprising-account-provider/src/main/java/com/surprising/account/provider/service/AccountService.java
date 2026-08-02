package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountLedgerQueryResponse;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminBalanceAdjustmentQueryResponse;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.ExpiringPositionSettlementAccountCommand;
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
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 账户统一业务入口。
 *
 * <p>余额和持仓命令只能进入 {@link AccountCommandGateway}，由用户分区 WAL 和 reducer 顺序裁决；
 * 在线余额、持仓和仓位模式只能读取本地 JVM 快照。数据库查询服务只用于账本、转账记录和后台
 * 审计等异步投影查询，不允许作为账户事实状态的回退来源。</p>
 */
@Service
public class AccountService {

    private final AccountProperties properties;
    private final AccountUserStateReducer stateReducer;
    private final AccountCommandGateway commandGateway;
    private final AccountQueryService projectionQueryService;

    @Autowired
    public AccountService(AccountProperties properties,
                          AccountUserStateReducer stateReducer,
                          AccountCommandGateway commandGateway,
                          AccountQueryService projectionQueryService) {
        this.properties = properties;
        this.stateReducer = stateReducer;
        this.commandGateway = commandGateway;
        this.projectionQueryService = projectionQueryService;
    }

    /** 管理员余额调整也必须进入同一用户分区，不能另开数据库事务。 */
    public BalanceResponse adjustBalance(BalanceAdjustmentRequest request) {
        requireRequest(request);
        return commandGateway.adjustBalance(request, null, null);
    }

    public BalanceResponse adminAdjustBalance(String adminUserId,
                                              String adminUsername,
                                              BalanceAdjustmentRequest request) {
        requireRequest(request);
        return commandGateway.adjustBalance(request, adminUserId, adminUsername);
    }

    public BalanceResponse balance(long userId, String asset) {
        PerpetualAccountStateUpdatedEvent snapshot = localSnapshot(userId);
        String normalizedAsset = normalizeAsset(asset);
        return snapshot.balances().stream()
                .filter(value -> value.asset().equalsIgnoreCase(normalizedAsset))
                .findFirst()
                .map(value -> new BalanceResponse(userId, value.asset(), value.availableUnits(), value.lockedUnits(),
                        Math.addExact(value.availableUnits(), value.lockedUnits()), snapshot.eventTime()))
                .orElseGet(() -> new BalanceResponse(userId, normalizedAsset, 0L, 0L, 0L, snapshot.eventTime()));
    }

    public BalanceQueryResponse balances(long userId) {
        PerpetualAccountStateUpdatedEvent snapshot = localSnapshot(userId);
        List<BalanceResponse> rows = snapshot.balances().stream()
                .map(value -> new BalanceResponse(userId, value.asset(), value.availableUnits(), value.lockedUnits(),
                        Math.addExact(value.availableUnits(), value.lockedUnits()), snapshot.eventTime()))
                .toList();
        return new BalanceQueryResponse(rows.size(), rows);
    }

    public ProductBalanceResponse adjustProductBalance(ProductBalanceAdjustmentRequest request) {
        requireRequest(request);
        requirePerpetualAccount(request.accountType());
        return commandGateway.adjustProductBalance(request, null, null);
    }

    public ProductBalanceResponse adminAdjustProductBalance(String adminUserId,
                                                            String adminUsername,
                                                            ProductBalanceAdjustmentRequest request) {
        requireRequest(request);
        requirePerpetualAccount(request.accountType());
        return commandGateway.adjustProductBalance(request, adminUserId, adminUsername);
    }

    public ProductBalanceResponse productBalance(long userId, AccountType accountType, String asset) {
        requirePerpetualAccount(accountType);
        BalanceResponse balance = balance(userId, asset);
        return new ProductBalanceResponse(userId, AccountType.USDT_PERPETUAL, balance.asset(),
                balance.availableUnits(), balance.lockedUnits(), balance.equityUnits(), balance.updatedAt());
    }

    public ProductBalanceQueryResponse productBalances(long userId, AccountType accountType) {
        requirePerpetualAccount(accountType);
        List<ProductBalanceResponse> rows = balances(userId).balances().stream()
                .map(value -> new ProductBalanceResponse(userId, AccountType.USDT_PERPETUAL, value.asset(),
                        value.availableUnits(), value.lockedUnits(), value.equityUnits(), value.updatedAt()))
                .toList();
        return new ProductBalanceQueryResponse(rows.size(), rows);
    }

    /** 账本是异步投影查询，不参与余额裁决。 */
    public AccountLedgerQueryResponse accountLedger(Long userId,
                                                    String asset,
                                                    String referenceType,
                                                    int limit) {
        return accountLedger(userId, asset, referenceType, limit, null, null);
    }

    public AccountLedgerQueryResponse accountLedger(Long userId,
                                                    String asset,
                                                    String referenceType,
                                                    int limit,
                                                    String cursor,
                                                    String sort) {
        requireUserId(userId);
        var page = projectionQueryService.accountLedgerPage(userId, optionalAsset(asset),
                optionalReferenceType(referenceType), normalizeLimit(limit), cursor, sort);
        return new AccountLedgerQueryResponse(page.items().size(), page.items(), page.nextCursor(), page.hasMore(),
                page.sort(), page.limit());
    }

    /** 产品账本是异步投影查询，不参与余额裁决。 */
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
        requireUserId(userId);
        requirePerpetualAccount(accountType);
        var page = projectionQueryService.productLedgerPage(userId, AccountType.USDT_PERPETUAL,
                optionalAsset(asset), optionalReferenceType(referenceType), normalizeLimit(limit), cursor, sort);
        return new ProductLedgerQueryResponse(page.items().size(), page.items(), page.nextCursor(), page.hasMore(),
                page.sort(), page.limit());
    }

    /** 转账记录是异步投影查询；转账命令在当前账户 reducer 未支持前失败关闭。 */
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
        requireUserId(userId);
        requirePerpetualAccount(accountType);
        var page = projectionQueryService.productTransferPage(userId, AccountType.USDT_PERPETUAL,
                optionalAsset(asset), normalizeLimit(limit), cursor, sort);
        return new ProductTransferRecordQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    /** 后台调整记录是异步审计查询。 */
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
        requireUserId(adminUserId);
        requireUserId(userId);
        if (accountType != null) {
            requirePerpetualAccount(accountType);
        }
        var page = projectionQueryService.adminBalanceAdjustmentPage(adminUserId, userId,
                optionalAdjustmentKind(adjustmentKind), accountType, optionalAsset(asset),
                optionalReferenceId(referenceId), normalizeLimit(limit), cursor, sort);
        return new AdminBalanceAdjustmentQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public ProductTransferResponse transfer(ProductTransferRequest request) {
        requireRequest(request);
        requirePerpetualAccount(request.sourceAccountType());
        requirePerpetualAccount(request.targetAccountType());
        return commandGateway.transfer(request);
    }

    public PositionModeResponse positionMode(long userId) {
        return positionMode(currentProductLine(), userId);
    }

    public PositionModeResponse positionMode(ProductLine productLine, long userId) {
        requireUserId(userId);
        requireCurrentProduct(productLine);
        PerpetualAccountStateUpdatedEvent snapshot = localSnapshot(userId);
        return new PositionModeResponse(ProductLine.LINEAR_PERPETUAL, userId, snapshot.positionMode(),
                snapshot.eventTime());
    }

    public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request) {
        requireRequest(request);
        requireCurrentProduct(ProductLine.LINEAR_PERPETUAL);
        return commandGateway.updatePositionMode(request);
    }

    public PositionResponse position(long userId, String symbol) {
        return position(userId, symbol, null, null);
    }

    public PositionResponse position(long userId, String symbol, String marginMode) {
        return position(userId, symbol, marginMode, null);
    }

    public PositionResponse position(long userId, String symbol, String marginMode, String positionSide) {
        requireUserId(userId);
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        com.surprising.trading.api.model.PositionSide normalizedPositionSide = normalizePositionSide(positionSide);
        return localPosition(localSnapshot(userId), userId, normalizedSymbol, normalizedMarginMode,
                normalizedPositionSide).orElseGet(() -> new PositionResponse(userId, normalizedSymbol, 0L,
                        normalizedMarginMode, normalizedPositionSide, 0L, 0L, 0L, Instant.EPOCH));
    }

    public PositionMarginResponse positionMargin(long userId, String symbol, String marginMode) {
        requireUserId(userId);
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        return localPositionMargin(localSnapshot(userId), userId, normalizedSymbol, normalizedMarginMode,
                com.surprising.trading.api.model.PositionSide.NET).orElseGet(() -> new PositionMarginResponse(
                        userId, normalizedSymbol, "", normalizedMarginMode,
                        com.surprising.trading.api.model.PositionSide.NET, 0L, Instant.EPOCH));
    }

    public PositionQueryResponse positions(long userId) {
        return positions(userId, null);
    }

    public PositionQueryResponse positions(long userId, String positionSide) {
        requireUserId(userId);
        com.surprising.trading.api.model.PositionSide normalized = positionSide == null || positionSide.isBlank()
                ? null : normalizePositionSide(positionSide);
        PerpetualAccountStateUpdatedEvent snapshot = localSnapshot(userId);
        List<PositionResponse> rows = snapshot.positions().stream()
                .filter(value -> normalized == null || value.positionSide() == normalized)
                .map(value -> toPositionResponse(userId, value))
                .toList();
        return new PositionQueryResponse(rows.size(), rows);
    }

    public PositionResponse adminPosition(long userId, String symbol, String marginMode, String positionSide) {
        return position(userId, symbol, marginMode, positionSide);
    }

    public PositionQueryResponse adminPositions(long userId, String positionSide) {
        return positions(userId, positionSide);
    }

    public PositionMarginAdjustmentResponse adjustPositionMargin(PositionMarginAdjustmentRequest request) {
        requireRequest(request);
        if (request.amountUnits() == 0L) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        requireCurrentProduct(ProductLine.LINEAR_PERPETUAL);
        return commandGateway.adjustPositionMargin(request);
    }

    /**
     * 交割和期权尚未接入用户分区 reducer 时必须停止，不得重新调用旧的数据库结算服务。
     * 接入对应产品线 reducer 后，这些入口改为追加生命周期命令。
     */
    public List<UserExpiringSettlementPlan> planDeliverySettlement(DeliverySettlementEvent event) {
        throw unsupportedLifecycle("交割结算");
    }

    public List<UserExpiringSettlementPlan> planOptionExercise(OptionExerciseEvent event) {
        throw unsupportedLifecycle("期权行权");
    }

    public record UserExpiringSettlementPlan(ProductLine productLine,
                                             long userId,
                                             ExpiringPositionSettlementAccountCommand command) {
    }

    private PerpetualAccountStateUpdatedEvent localSnapshot(long userId) {
        requireUserId(userId);
        if (currentProductLine() != ProductLine.LINEAR_PERPETUAL) {
            throw new IllegalStateException("当前产品线尚未接入账户用户分区快照: " + currentProductLine());
        }
        return stateReducer.snapshot(new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, userId))
                .orElseThrow(() -> new AccountStateUnavailableException("账户 JVM 快照尚未初始化: " + userId));
    }

    private Optional<PositionResponse> localPosition(PerpetualAccountStateUpdatedEvent snapshot,
                                                      long userId,
                                                      String symbol,
                                                      MarginMode marginMode,
                                                      com.surprising.trading.api.model.PositionSide positionSide) {
        return snapshot.positions().stream()
                .filter(value -> value.symbol().equalsIgnoreCase(symbol))
                .filter(value -> value.marginMode() == marginMode)
                .filter(value -> value.positionSide() == positionSide)
                .map(value -> toPositionResponse(userId, value))
                .findFirst();
    }

    private Optional<PositionMarginResponse> localPositionMargin(PerpetualAccountStateUpdatedEvent snapshot,
                                                                  long userId,
                                                                  String symbol,
                                                                  MarginMode marginMode,
                                                                  com.surprising.trading.api.model.PositionSide side) {
        return snapshot.positionMargins().stream()
                .filter(value -> value.symbol().equalsIgnoreCase(symbol))
                .filter(value -> value.marginMode() == marginMode)
                .filter(value -> value.positionSide() == side)
                .map(value -> new PositionMarginResponse(userId, value.symbol(), value.asset(), value.marginMode(),
                        value.positionSide(), value.marginUnits(), snapshot.eventTime()))
                .findFirst();
    }

    private PositionResponse toPositionResponse(long userId,
                                                PerpetualAccountStateUpdatedEvent.Position position) {
        return new PositionResponse(userId, position.symbol(), position.instrumentVersion(), position.marginMode(),
                position.positionSide(), position.signedQuantitySteps(), position.entryPriceTicks(),
                position.realizedPnlUnits(), position.updatedAt());
    }

    private ProductLine currentProductLine() {
        AccountProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        if (kafka == null || kafka.getProductLine() == null || !kafka.isProductTopicsEnabled()) {
            throw new IllegalStateException("account 未配置唯一产品线");
        }
        return kafka.getProductLine();
    }

    private void requireCurrentProduct(ProductLine productLine) {
        ProductLine current = currentProductLine();
        ProductLineConfiguration.requireSame(current, productLine, "account.product-line");
        if (current != ProductLine.LINEAR_PERPETUAL) {
            throw new IllegalStateException("当前产品线尚未接入账户用户分区 reducer: " + current);
        }
    }

    private void requirePerpetualAccount(AccountType accountType) {
        if (accountType != AccountType.USDT_PERPETUAL) {
            throw new IllegalStateException("当前账户入口只支持永续账户快照: " + accountType);
        }
        requireCurrentProduct(ProductLine.LINEAR_PERPETUAL);
    }

    private static RuntimeException unsupportedLifecycle(String operation) {
        return new IllegalStateException(operation + "尚未接入账户用户分区 reducer，禁止回退数据库");
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
    }

    private static void requireUserId(Long userId) {
        if (userId == null || userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
    }

    private static String normalizeAsset(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String value = asset.trim().toUpperCase(java.util.Locale.ROOT);
        if (!value.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + asset);
        }
        return value;
    }

    private static String optionalAsset(String asset) {
        return asset == null || asset.isBlank() ? null : normalizeAsset(asset);
    }

    private static String optionalReferenceType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_:-]{2,80}")) {
            throw new IllegalArgumentException("invalid referenceType: " + value);
        }
        return normalized;
    }

    private static String optionalAdjustmentKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_:-]{2,80}")) {
            throw new IllegalArgumentException("invalid adjustmentKind: " + value);
        }
        return normalized;
    }

    private static String optionalReferenceId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("referenceId is too long");
        }
        return normalized;
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }

    private static MarginMode normalizeMarginMode(String value) {
        return value == null || value.isBlank() ? MarginMode.CROSS : MarginMode.valueOf(value.trim().toUpperCase());
    }

    private static com.surprising.trading.api.model.PositionSide normalizePositionSide(String value) {
        return value == null || value.isBlank()
                ? com.surprising.trading.api.model.PositionSide.NET
                : com.surprising.trading.api.model.PositionSide.valueOf(value.trim().toUpperCase());
    }

    private static int normalizeLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        return limit;
    }
}
