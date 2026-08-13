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
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
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
    private final AccountAeronGateway aeronGateway;
    private final AccountQueryService projectionQueryService;

    @Autowired
    public AccountService(AccountProperties properties,
                          AccountUserStateReducer stateReducer,
                          AccountCommandGateway commandGateway,
                          AccountAeronGateway aeronGateway,
                          AccountQueryService projectionQueryService) {
        this.properties = properties;
        this.stateReducer = stateReducer;
        this.commandGateway = commandGateway;
        this.aeronGateway = aeronGateway;
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
        CoreUserStateView snapshot = coreSnapshot(currentProductLine(), userId);
        String normalizedAsset = normalizeAsset(asset);
        return snapshot.balances().stream()
                .filter(value -> value.asset().equalsIgnoreCase(normalizedAsset))
                .findFirst()
                .map(value -> new BalanceResponse(userId, value.asset(), value.availableUnits(), value.lockedUnits(),
                        Math.addExact(value.availableUnits(), value.lockedUnits()), Instant.now()))
                .orElseGet(() -> new BalanceResponse(userId, normalizedAsset, 0L, 0L, 0L, Instant.now()));
    }

    public BalanceQueryResponse balances(long userId) {
        CoreUserStateView snapshot = coreSnapshot(currentProductLine(), userId);
        List<BalanceResponse> rows = snapshot.balances().stream()
                .map(value -> new BalanceResponse(userId, value.asset(), value.availableUnits(), value.lockedUnits(),
                        Math.addExact(value.availableUnits(), value.lockedUnits()), Instant.now()))
                .toList();
        return new BalanceQueryResponse(rows.size(), rows);
    }

    public ProductBalanceResponse adjustProductBalance(ProductBalanceAdjustmentRequest request) {
        requireRequest(request);
        requireProductAccount(request.accountType());
        return commandGateway.adjustProductBalance(request, null, null);
    }

    public ProductBalanceResponse adminAdjustProductBalance(String adminUserId,
                                                            String adminUsername,
                                                            ProductBalanceAdjustmentRequest request) {
        requireRequest(request);
        requireProductAccount(request.accountType());
        return commandGateway.adjustProductBalance(request, adminUserId, adminUsername);
    }

    public ProductBalanceResponse productBalance(long userId, AccountType accountType, String asset) {
        requireProductAccount(accountType);
        CoreUserStateView snapshot = coreSnapshot(accountType.productLine().orElseThrow(), userId);
        String normalizedAsset = normalizeAsset(asset);
        BalanceResponse balance = snapshot.balances().stream()
                .filter(value -> value.asset().equalsIgnoreCase(normalizedAsset))
                .findFirst()
                .map(value -> new BalanceResponse(userId, value.asset(), value.availableUnits(), value.lockedUnits(),
                        Math.addExact(value.availableUnits(), value.lockedUnits()), Instant.now()))
                .orElseGet(() -> new BalanceResponse(userId, normalizedAsset, 0L, 0L, 0L, Instant.now()));
        return new ProductBalanceResponse(userId, accountType, balance.asset(),
                balance.availableUnits(), balance.lockedUnits(), balance.equityUnits(), balance.updatedAt());
    }

    public ProductBalanceQueryResponse productBalances(long userId, AccountType accountType) {
        requireProductAccount(accountType);
        CoreUserStateView snapshot = coreSnapshot(accountType.productLine().orElseThrow(), userId);
        List<ProductBalanceResponse> rows = snapshot.balances().stream()
                .map(value -> new ProductBalanceResponse(userId, accountType, value.asset(),
                        value.availableUnits(), value.lockedUnits(),
                        Math.addExact(value.availableUnits(), value.lockedUnits()), Instant.now()))
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
        requireProductAccount(accountType);
        var page = projectionQueryService.productLedgerPage(userId, accountType,
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
        var page = projectionQueryService.productTransferPage(userId, accountType,
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
            requireProductAccount(accountType);
        }
        var page = projectionQueryService.adminBalanceAdjustmentPage(adminUserId, userId,
                optionalAdjustmentKind(adjustmentKind), accountType, optionalAsset(asset),
                optionalReferenceId(referenceId), normalizeLimit(limit), cursor, sort);
        return new AdminBalanceAdjustmentQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public ProductTransferResponse transfer(ProductTransferRequest request) {
        requireRequest(request);
        requireProductAccount(request.sourceAccountType());
        requireProductAccount(request.targetAccountType());
        return commandGateway.transfer(request);
    }

    public PositionModeResponse positionMode(long userId) {
        return positionMode(currentProductLine(), userId);
    }

    public PositionModeResponse positionMode(ProductLine productLine, long userId) {
        requireUserId(userId);
        requireCurrentProduct(productLine);
        requireDerivativeProduct(productLine);
        CoreUserStateView snapshot = coreSnapshot(productLine, userId);
        return new PositionModeResponse(productLine, userId,
                com.surprising.trading.api.model.PositionMode.valueOf(snapshot.positionMode().name()), Instant.now());
    }

    public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request) {
        requireRequest(request);
        requireDerivativeProduct(currentProductLine());
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
        requireDerivativeProduct(currentProductLine());
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        com.surprising.trading.api.model.PositionSide normalizedPositionSide = normalizePositionSide(positionSide);
        return corePosition(coreSnapshot(currentProductLine(), userId), userId, normalizedSymbol, normalizedMarginMode,
                normalizedPositionSide).orElseGet(() -> new PositionResponse(userId, normalizedSymbol, 0L,
                        normalizedMarginMode, normalizedPositionSide, 0L, 0L, 0L, Instant.EPOCH));
    }

    public PositionMarginResponse positionMargin(long userId, String symbol, String marginMode) {
        requireUserId(userId);
        requireDerivativeProduct(currentProductLine());
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = normalizeMarginMode(marginMode);
        return corePositionMargin(coreSnapshot(currentProductLine(), userId), userId, normalizedSymbol, normalizedMarginMode,
                com.surprising.trading.api.model.PositionSide.NET).orElseGet(() -> new PositionMarginResponse(
                        userId, normalizedSymbol, "", normalizedMarginMode,
                        com.surprising.trading.api.model.PositionSide.NET, 0L, Instant.EPOCH));
    }

    public PositionQueryResponse positions(long userId) {
        return positions(userId, null);
    }

    public PositionQueryResponse positions(long userId, String positionSide) {
        requireUserId(userId);
        requireDerivativeProduct(currentProductLine());
        com.surprising.trading.api.model.PositionSide normalized = positionSide == null || positionSide.isBlank()
                ? null : normalizePositionSide(positionSide);
        CoreUserStateView snapshot = coreSnapshot(currentProductLine(), userId);
        List<PositionResponse> rows = snapshot.positions().stream()
                .filter(value -> normalized == null || value.positionSide().name().equals(normalized.name()))
                .map(value -> toCorePositionResponse(userId, value))
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
        requireDerivativeProduct(currentProductLine());
        return commandGateway.adjustPositionMargin(request);
    }

    public List<UserExpiringSettlementPlan> planDeliverySettlement(DeliverySettlementEvent event) {
        if (event == null || event.status() != com.surprising.instrument.api.model.InstrumentStatus.CLOSED) {
            throw new IllegalArgumentException("交割结算事件必须是 CLOSED");
        }
        if (event.settlementPriceTicks() <= 0L || event.settlementMethod() != ContractSettlementMethod.CASH) {
            throw new IllegalArgumentException("交割结算必须携带有效现金结算价");
        }
        InstrumentResponse instrument = requireLifecycleInstrument(event.instrument(), event.symbol(), event.version());
        if (instrument.contractType() != event.contractType() || !instrument.contractType().isDelivery()) {
            throw new IllegalArgumentException("交割事件合约类型不匹配");
        }
        ProductLine productLine = instrument.contractType().productLine();
        Instant settlementTime = event.deliveryTime() != null ? event.deliveryTime() : event.eventTime();
        return stateReducer.partitionsForSymbol(productLine, instrument.symbol()).stream()
                .flatMap(partition -> stateReducer.snapshot(partition).orElseThrow().positions().stream()
                        .filter(position -> position.symbol().equalsIgnoreCase(instrument.symbol())
                                && position.signedQuantitySteps() != 0L)
                        .map(position -> new UserExpiringSettlementPlan(productLine, partition.userId(),
                                new ExpiringPositionSettlementAccountCommand(instrument.symbol(), position.instrumentVersion(),
                                        position.marginMode(), position.positionSide(), event.settlementPriceTicks(), 0L,
                                        "DELIVERY_SETTLEMENT", "DELIVERY_SETTLEMENT", settlementTime))))
                .toList();
    }

    public List<UserExpiringSettlementPlan> planOptionExercise(OptionExerciseEvent event) {
        if (event == null || event.status() != com.surprising.instrument.api.model.InstrumentStatus.CLOSED) {
            throw new IllegalArgumentException("期权行权事件必须是 CLOSED");
        }
        if (event.settlementMethod() != ContractSettlementMethod.CASH
                || event.underlyingSettlementPriceUnits() <= 0L) {
            throw new IllegalArgumentException("期权行权必须携带有效标的结算价");
        }
        InstrumentResponse instrument = requireLifecycleInstrument(event.instrument(), event.symbol(), event.version());
        if (instrument.contractType() != ContractType.VANILLA_OPTION
                || instrument.optionType() != event.optionType()
                || instrument.optionExerciseStyle() != OptionExerciseStyle.EUROPEAN
                || !normalizeSymbol(event.underlyingSymbol()).equals(normalizeSymbol(instrument.underlyingSymbol()))) {
            throw new IllegalArgumentException("期权行权事件合约快照不匹配");
        }
        Instant settlementTime = event.deliveryTime() != null ? event.deliveryTime() : event.eventTime();
        ProductLine productLine = ProductLine.OPTION;
        return stateReducer.partitionsForSymbol(productLine, instrument.symbol()).stream()
                .flatMap(partition -> stateReducer.snapshot(partition).orElseThrow().positions().stream()
                        .filter(position -> position.symbol().equalsIgnoreCase(instrument.symbol())
                                && position.signedQuantitySteps() != 0L)
                        .map(position -> new UserExpiringSettlementPlan(productLine, partition.userId(),
                                new ExpiringPositionSettlementAccountCommand(instrument.symbol(), position.instrumentVersion(),
                                        position.marginMode(), position.positionSide(), 0L,
                                        event.cashSettlementUnitsPerContract(),
                                        "OPTION_EXERCISE", "OPTION_EXERCISE", settlementTime))))
                .toList();
    }

    public record UserExpiringSettlementPlan(ProductLine productLine,
                                             long userId,
                                             ExpiringPositionSettlementAccountCommand command) {
    }

    private PerpetualAccountStateUpdatedEvent localSnapshot(ProductLine productLine, long userId) {
        requireUserId(userId);
        requireCurrentProduct(productLine);
        return stateReducer.snapshot(new UserPartitionKey(productLine, userId))
                .orElseThrow(() -> new AccountStateUnavailableException("账户 JVM 快照尚未初始化: "
                        + productLine + ":" + userId));
    }

    private CoreUserStateView coreSnapshot(ProductLine productLine, long userId) {
        requireUserId(userId);
        requireCurrentProduct(productLine);
        CoreUserStateView state = aeronGateway.userState(userId);
        if (state == null) throw new AccountStateUnavailableException("Aeron 账户状态尚未初始化: "
                + productLine + ':' + userId);
        return state;
    }

    private Optional<PositionResponse> corePosition(CoreUserStateView snapshot, long userId, String symbol,
                                                     MarginMode marginMode,
                                                     com.surprising.trading.api.model.PositionSide positionSide) {
        return snapshot.positions().stream().filter(value -> value.symbol().equalsIgnoreCase(symbol))
                .filter(value -> value.marginMode().name().equals(marginMode.name()))
                .filter(value -> value.positionSide().name().equals(positionSide.name()))
                .map(value -> toCorePositionResponse(userId, value)).findFirst();
    }

    private Optional<PositionMarginResponse> corePositionMargin(CoreUserStateView snapshot, long userId,
                                                                 String symbol, MarginMode marginMode,
                                                                 com.surprising.trading.api.model.PositionSide side) {
        return snapshot.positions().stream().filter(value -> value.symbol().equalsIgnoreCase(symbol))
                .filter(value -> value.marginMode().name().equals(marginMode.name()))
                .filter(value -> value.positionSide().name().equals(side.name()))
                .map(value -> new PositionMarginResponse(userId, value.symbol(), value.marginAsset(), marginMode,
                        side, value.positionMarginUnits(), Instant.now())).findFirst();
    }

    private PositionResponse toCorePositionResponse(long userId,
                                                     com.surprising.aeron.protocol.CorePositionView position) {
        return new PositionResponse(userId, position.symbol(), position.instrumentVersion(),
                MarginMode.valueOf(position.marginMode().name()),
                com.surprising.trading.api.model.PositionSide.valueOf(position.positionSide().name()),
                position.signedQuantitySteps(), position.entryPriceTicks(), position.realizedPnlUnits(), Instant.now());
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
    }

    private void requireProductAccount(AccountType accountType) {
        if (accountType == null || accountType == AccountType.FUNDING
                || accountType.productLine().isEmpty()) {
            throw new IllegalArgumentException("产品账户类型无效: " + accountType);
        }
        requireCurrentProduct(accountType.productLine().orElseThrow());
    }

    private void requireDerivativeProduct(ProductLine productLine) {
        requireCurrentProduct(productLine);
        if (productLine == ProductLine.SPOT) {
            throw new IllegalStateException("现货账户不支持持仓业务");
        }
    }

    private InstrumentResponse requireLifecycleInstrument(InstrumentResponse instrument,
                                                          String symbol,
                                                          long version) {
        if (instrument == null || !normalizeSymbol(symbol).equals(normalizeSymbol(instrument.symbol()))
                || instrument.version() != version) {
            throw new IllegalArgumentException("生命周期事件缺少匹配的不可变 instrument 快照");
        }
        return instrument;
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
