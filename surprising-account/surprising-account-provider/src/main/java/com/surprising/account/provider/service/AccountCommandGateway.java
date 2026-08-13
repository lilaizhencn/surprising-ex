package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountCommandGateway {

    private final AccountProperties properties;
    private final AccountAeronGateway aeron;

    public AccountCommandGateway(AccountProperties properties, AccountAeronGateway aeron) {
        this.properties = properties;
        this.aeron = aeron;
    }

    public BalanceResponse adjustBalance(BalanceAdjustmentRequest request, String adminUserId, String adminUsername) {
        UUID commandId = commandId("balance-adjust", request.userId(), request.referenceId());
        aeron.command(CoreMessageType.ADJUST_BALANCE, commandId, request.userId(),
                TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand(request.asset(), request.amountUnits())));
        CoreUserStateView state = requireUserState(request.userId());
        return balance(state, request.asset());
    }

    public ProductBalanceResponse adjustProductBalance(ProductBalanceAdjustmentRequest request,
                                                        String adminUserId, String adminUsername) {
        requireProductAccount(request.accountType(), "产品余额调整");
        UUID commandId = commandId("product-balance-adjust", request.userId(), request.referenceId());
        aeron.command(CoreMessageType.ADJUST_BALANCE, commandId, request.userId(),
                TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand(request.asset(), request.amountUnits())));
        BalanceResponse balance = balance(requireUserState(request.userId()), request.asset());
        return new ProductBalanceResponse(request.userId(), request.accountType(), balance.asset(),
                balance.availableUnits(), balance.lockedUnits(), balance.equityUnits(), balance.updatedAt());
    }

    public ProductTransferResponse transfer(ProductTransferRequest request) {
        throw new IllegalStateException("跨产品线划转必须经 surprising-gateway 编排");
    }

    public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request) {
        UUID commandId = commandId("position-mode", request.userId(), request.referenceId());
        aeron.command(CoreMessageType.UPDATE_POSITION_MODE, commandId, request.userId(),
                TradingCommandCodec.encodeUpdatePositionMode(new UpdatePositionModeCommand(
                        CorePositionMode.valueOf(request.positionMode().name()))));
        CoreUserStateView state = requireUserState(request.userId());
        return new PositionModeResponse(state.productLine(), state.userId(),
                PositionMode.valueOf(state.positionMode().name()), Instant.now());
    }

    public PositionMarginAdjustmentResponse adjustPositionMargin(PositionMarginAdjustmentRequest request) {
        UUID commandId = commandId("position-margin", request.userId(), request.referenceId());
        aeron.command(CoreMessageType.ADJUST_POSITION_MARGIN, commandId, request.userId(),
                TradingCommandCodec.encodeAdjustPositionMargin(new AdjustPositionMarginCommand(
                        request.symbol(), CoreMarginMode.valueOf(request.marginMode().name()),
                        CorePositionSide.valueOf(request.positionSide().name()), request.amountUnits())));
        CoreUserStateView state = requireUserState(request.userId());
        var position = state.positions().stream()
                .filter(value -> value.symbol().equalsIgnoreCase(request.symbol()))
                .filter(value -> value.marginMode().name().equals(request.marginMode().name()))
                .filter(value -> value.positionSide().name().equals(request.positionSide().name()))
                .findFirst().orElseThrow(() -> new AccountStateUnavailableException("Aeron position missing"));
        BalanceResponse balance = balance(state, position.marginAsset());
        return new PositionMarginAdjustmentResponse(request.userId(), position.symbol(), position.marginAsset(),
                MarginMode.valueOf(position.marginMode().name()), PositionSide.valueOf(position.positionSide().name()),
                request.amountUnits(), position.positionMarginUnits(), balance.availableUnits(),
                balance.lockedUnits(), balance.equityUnits(), request.referenceId(), Instant.now());
    }

    private CoreUserStateView requireUserState(long userId) {
        CoreUserStateView state = aeron.userState(userId);
        if (state == null) throw new AccountStateUnavailableException("Aeron user state missing: " + userId);
        return state;
    }

    private static BalanceResponse balance(CoreUserStateView state, String asset) {
        String normalized = asset.trim().toUpperCase(java.util.Locale.ROOT);
        return state.balances().stream().filter(value -> value.asset().equals(normalized)).findFirst()
                .map(value -> new BalanceResponse(state.userId(), value.asset(), value.availableUnits(),
                        value.lockedUnits(), Math.addExact(value.availableUnits(), value.lockedUnits()), Instant.now()))
                .orElseGet(() -> new BalanceResponse(state.userId(), normalized, 0, 0, 0, Instant.now()));
    }

    private UUID commandId(String operation, long userId, String referenceId) {
        if (referenceId == null || referenceId.isBlank()) throw new IllegalArgumentException("referenceId is required");
        String identity = properties.getKafka().getProductLine() + ":" + userId + ":" + operation + ":"
                + referenceId.trim();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private void requireProductAccount(AccountType accountType, String operation) {
        ProductLine current = properties.getKafka().getProductLine();
        if (accountType == null || accountType.productLine().orElse(null) != current) {
            throw new IllegalStateException(operation + "的账户类型与当前产品线不匹配: " + current);
        }
    }
}
