package com.surprising.account.provider.service;

import com.surprising.account.provider.exception.AccountStateUnavailableException;
import com.surprising.account.api.model.AccountType;
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
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 账户统一业务入口。
 *
 * <p>余额和持仓命令只能通过 {@link AccountCommandGateway} 进入 Aeron Cluster 顺序裁决；在线余额、
 * 持仓和仓位模式读取 realtime 的完整用户视图。数据库查询服务只用于账本、转账记录和后台审计等
 * 异步投影查询，不允许作为账户事实状态的回退来源。</p>
 */
@Service
public class AccountService {
    private final com.surprising.realtime.api.ValkeyUserQueries realtimeQueries;

    private final AccountProperties properties;
    private final AccountCommandGateway commandGateway;

    @Autowired
    public AccountService(AccountProperties properties,
                          AccountCommandGateway commandGateway,
                          com.surprising.realtime.api.ValkeyUserQueries realtimeQueries) {
        this.properties = properties;
        this.commandGateway = commandGateway;
        this.realtimeQueries = java.util.Objects.requireNonNull(realtimeQueries, "realtime queries are required");
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
        requireUserId(userId);
        var balance = realtimeQueries.balance(currentProductLine(), userId, asset, null);
        return balance
                .map(value -> new BalanceResponse(userId, value.asset(), value.availableUnits(), value.lockedUnits(),
                        Math.addExact(value.availableUnits(), value.lockedUnits()), Instant.now()))
                .orElseGet(() -> new BalanceResponse(userId, asset, 0L, 0L, 0L, Instant.now()));
    }

    public BalanceQueryResponse balances(long userId) {
        CoreUserStateView snapshot = accountSnapshot(currentProductLine(), userId);
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
        requireUserId(userId);
        ProductLine product = accountType.productLine().orElseThrow();
        requireCurrentProduct(product);
        var selected = realtimeQueries.balance(product, userId, asset, null);
        BalanceResponse balance = selected
                .map(value -> new BalanceResponse(userId, value.asset(), value.availableUnits(), value.lockedUnits(),
                        Math.addExact(value.availableUnits(), value.lockedUnits()), Instant.now()))
                .orElseGet(() -> new BalanceResponse(userId, asset, 0L, 0L, 0L, Instant.now()));
        return new ProductBalanceResponse(userId, accountType, balance.asset(),
                balance.availableUnits(), balance.lockedUnits(), balance.equityUnits(), balance.updatedAt());
    }

    public ProductBalanceQueryResponse productBalances(long userId, AccountType accountType) {
        requireProductAccount(accountType);
        CoreUserStateView snapshot = accountSnapshot(accountType.productLine().orElseThrow(), userId);
        List<ProductBalanceResponse> rows = snapshot.balances().stream()
                .map(value -> new ProductBalanceResponse(userId, accountType, value.asset(),
                        value.availableUnits(), value.lockedUnits(),
                        Math.addExact(value.availableUnits(), value.lockedUnits()), Instant.now()))
                .toList();
        return new ProductBalanceQueryResponse(rows.size(), rows);
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
        CoreUserStateView snapshot = accountSnapshot(productLine, userId);
        return new PositionModeResponse(productLine, userId,
                com.surprising.trading.api.model.PositionMode.valueOf(snapshot.positionMode().name()), Instant.now());
    }

    public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request) {
        requireRequest(request);
        requireDerivativeProduct(currentProductLine());
        return commandGateway.updatePositionMode(request);
    }

    public PositionResponse position(long userId, String instrumentId) {
        return position(userId, instrumentId, MarginMode.CROSS, com.surprising.trading.api.model.PositionSide.NET);
    }

    public PositionResponse position(long userId, String instrumentId, MarginMode marginMode) {
        return position(userId, instrumentId, marginMode, com.surprising.trading.api.model.PositionSide.NET);
    }

    public PositionResponse position(long userId, String instrumentId, MarginMode marginMode, com.surprising.trading.api.model.PositionSide positionSide) {
        requireUserId(userId);
        requireDerivativeProduct(currentProductLine());
        var position = realtimeQueries.position(currentProductLine(), userId, instrumentId,
                    com.surprising.aeron.protocol.CoreMarginMode.valueOf(marginMode.name()),
                    com.surprising.aeron.protocol.CorePositionSide.valueOf(positionSide.name()), null)
                    .map(value -> toCorePositionResponse(userId, value));
        return position.orElseGet(() -> new PositionResponse(userId, instrumentId,
                        marginMode, positionSide, 0L, 0L, 0L, Instant.EPOCH));
    }

    public PositionMarginResponse positionMargin(long userId, String instrumentId, MarginMode marginMode) {
        requireUserId(userId);
        requireDerivativeProduct(currentProductLine());
        var position = realtimeQueries.position(currentProductLine(), userId, instrumentId,
                    com.surprising.aeron.protocol.CoreMarginMode.valueOf(marginMode.name()),
                    com.surprising.aeron.protocol.CorePositionSide.NET, null)
                    .map(value -> new PositionMarginResponse(userId, value.instrumentId(), value.marginAsset(),
                            marginMode, com.surprising.trading.api.model.PositionSide.NET,
                            value.positionMarginUnits(), Instant.now()));
        return position.orElseGet(() -> new PositionMarginResponse(
                        userId, instrumentId, "", marginMode,
                        com.surprising.trading.api.model.PositionSide.NET, 0L, Instant.EPOCH));
    }

    public PositionQueryResponse positions(long userId) {
        return positions(userId, null);
    }

    public PositionQueryResponse positions(long userId, com.surprising.trading.api.model.PositionSide positionSide) {
        requireUserId(userId);
        requireDerivativeProduct(currentProductLine());
        CoreUserStateView snapshot = accountSnapshot(currentProductLine(), userId);
        List<PositionResponse> rows = snapshot.positions().stream()
                .filter(value -> positionSide == null || value.positionSide().name().equals(positionSide.name()))
                .map(value -> toCorePositionResponse(userId, value))
                .toList();
        return new PositionQueryResponse(rows.size(), rows);
    }

    public PositionResponse adminPosition(long userId, String instrumentId, MarginMode marginMode, com.surprising.trading.api.model.PositionSide positionSide) {
        return position(userId, instrumentId, marginMode, positionSide);
    }

    public PositionQueryResponse adminPositions(long userId, com.surprising.trading.api.model.PositionSide positionSide) {
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

    private CoreUserStateView accountSnapshot(ProductLine productLine, long userId) {
        requireUserId(userId);
        requireCurrentProduct(productLine);
        CoreUserStateView state = realtimeQueries.require(productLine,userId,null).account();
        if (state == null || state.productLine() != productLine || state.userId() != userId)
            throw new AccountStateUnavailableException("realtime 账户状态尚未初始化: "
                + productLine + ':' + userId);
        return state;
    }

    private PositionResponse toCorePositionResponse(long userId,
                                                     com.surprising.aeron.protocol.CorePositionView position) {
        return new PositionResponse(userId, position.instrumentId(),
                MarginMode.valueOf(position.marginMode().name()),
                com.surprising.trading.api.model.PositionSide.valueOf(position.positionSide().name()),
                position.signedQuantitySteps(), position.entryPriceTicks(), position.realizedPnlUnits(), Instant.now());
    }

    private ProductLine currentProductLine() {
        AccountProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        if (kafka == null || kafka.getProductLine() == null) {
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
            throw new IllegalArgumentException("现货账户不支持持仓业务");
        }
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

}
