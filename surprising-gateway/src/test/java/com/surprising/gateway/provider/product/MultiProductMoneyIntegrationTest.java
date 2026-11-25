package com.surprising.gateway.provider.product;

import com.surprising.account.api.model.*;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.account.provider.repository.BalanceAdjustmentRepository;
import com.surprising.account.provider.service.*;
import com.surprising.aeron.protocol.*;
import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.model.ProductTransferStatus;
import com.surprising.gateway.provider.service.*;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 六条产品线账户状态隔离、幂等与跨产品线资金划转守恒测试（协议层内存账本桩）。 */
class MultiProductMoneyIntegrationTest {
    @Test
    void oneGatewayKeepsSixBalancesIsolatedAndCrossProductRetriesConserveFunds() {
        var products = mock(GatewayProductServices.class);
        when(products.enabled()).thenReturn(List.of(ProductLine.values()));
        var cores = new EnumMap<ProductLine, Core>(ProductLine.class);
        try {
            for (var line : ProductLine.values()) {
                var core = new Core(line);
                cores.put(line, core);
                when(products.service(line, AccountCommandGateway.class)).thenReturn(core.accounts);
                core.accounts.adjustBalance(new BalanceAdjustmentRequest(42, "USDT", 100_000L * (line.ordinal() + 1),
                        "same-deposit-reference", "test deposit"), null, null);
                // 同一业务请求重试，不能重复入账。
                core.accounts.adjustBalance(new BalanceAdjustmentRequest(42, "USDT", 100_000L * (line.ordinal() + 1),
                        "same-deposit-reference", "test deposit"), null, null);
                assertThat(core.balance()).isEqualTo(100_000L * (line.ordinal() + 1));
            }
            long initial = total(cores);
            var transfers = new ProductTransferService(products);
            for (var line : ProductLine.values()) {
                if (line == ProductLine.SPOT) continue;
                var command = transfer(ProductLine.SPOT, line, "allocation-" + line);
                assertThat(transfers.transfer(command).status()).isEqualTo(ProductTransferStatus.COMPLETED);
                assertThat(transfers.transfer(command).status()).isEqualTo(ProductTransferStatus.COMPLETED);
                assertThat(total(cores)).isEqualTo(initial);
                assertThat(transfers.transfer(transfer(line, ProductLine.SPOT, "return-" + line)).status())
                        .isEqualTo(ProductTransferStatus.COMPLETED);
                assertThat(total(cores)).isEqualTo(initial);
            }
            var spot = cores.get(ProductLine.SPOT);
            var perpetual = cores.get(ProductLine.LINEAR_PERPETUAL);
            spot.dropReply.set(true);
            var lostSourceReply = transfer(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, "source-reply-lost");
            assertThat(transfers.transfer(lostSourceReply).status()).isEqualTo(ProductTransferStatus.PENDING);
            assertThat(spot.accounts.pendingTransfers(10)).hasSize(1);
            // 提交结果丢失，源 Core 恢复后通过原来的划转标识重放。
            spot.restart();
            transfers.reconcile(100);
            assertThat(spot.accounts.pendingTransfers(10)).isEmpty();
            assertThat(total(cores)).isEqualTo(initial);

            perpetual.dropReply.set(true);
            var lostTargetReply = transfer(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, "target-reply-lost");
            assertThat(transfers.transfer(lostTargetReply).status()).isEqualTo(ProductTransferStatus.SOURCE_DEBITED);
            long targetAfterCommit = perpetual.balance();
            perpetual.restart();
            transfers.reconcile(100);
            assertThat(perpetual.balance()).isEqualTo(targetAfterCommit);
            assertThat(total(cores)).isEqualTo(initial);

            var custody = products.service(ProductLine.SPOT, AccountCommandGateway.class);
            long derivativeBefore = perpetual.balance();
            custody.adjustBalance(new BalanceAdjustmentRequest(42, "USDT", -500, "withdrawal-one", "withdrawal"), null, null);
            custody.adjustBalance(new BalanceAdjustmentRequest(42, "USDT", -500, "withdrawal-one", "withdrawal"), null, null);
            assertThat(perpetual.balance()).isEqualTo(derivativeBefore);
            assertThat(total(cores)).isEqualTo(initial - 500);
            custody.adjustBalance(new BalanceAdjustmentRequest(42, "USDT", 500, "withdrawal-one-refund", "refund"), null, null);
            assertThat(total(cores)).isEqualTo(initial);

            var excessive = new ProductTransferCommand(42, "overspend", AccountType.SPOT, AccountType.USDT_PERPETUAL,
                    "USDT", Long.MAX_VALUE, "overspend", "test");
            assertThat(transfers.transfer(excessive).status()).isEqualTo(ProductTransferStatus.FAILED);
            assertThat(total(cores)).isEqualTo(initial);
        } finally {
            cores.values().forEach(Core::close);
        }
    }

    private ProductTransferCommand transfer(ProductLine source, ProductLine target, String key) {
        return new ProductTransferCommand(42, key, AccountType.valueOf(source.accountTypeCode()),
                AccountType.valueOf(target.accountTypeCode()), "USDT", 1000, key, "allocation");
    }

    private long total(Map<ProductLine, Core> cores) {
        return cores.values().stream().mapToLong(Core::balance).sum();
    }

    private static final class Core implements AutoCloseable {
        final ProductLine line;
        final Map<Long, Long> balances = new ConcurrentHashMap<>();
        final Set<UUID> appliedCommands = ConcurrentHashMap.newKeySet();
        final Map<Long, TransferFundsCommand> pendingTransfers = new ConcurrentHashMap<>();
        long sequence;
        final AtomicBoolean dropReply = new AtomicBoolean();
        final AccountCommandGateway accounts;

        Core(ProductLine line) {
            this.line = line;
            var properties = new AccountProperties();
            properties.getKafka().setProductLine(line);
            var aeron = mock(AccountAeronGateway.class);
            when(aeron.command(any(), any(), anyLong(), any())).thenAnswer(call -> {
                CoreMessageType type = call.getArgument(0);
                UUID commandId = call.getArgument(1);
                long userId = call.getArgument(2);
                byte[] payload = call.getArgument(3);
                long seq = ++sequence;
                if (appliedCommands.add(commandId)) {
                    if (type == CoreMessageType.ADJUST_BALANCE) {
                        var cmd = TradingCommandCodec.decodeBalanceAdjustment(payload);
                        long cur = balances.getOrDefault(userId, 0L);
                        long next = cur + cmd.deltaUnits();
                        if (next < 0) {
                            appliedCommands.remove(commandId);
                            throw new AccountCommandRejectedException("INSUFFICIENT_BALANCE", "INSUFFICIENT_BALANCE");
                        }
                        balances.put(userId, next);
                    } else if (type == CoreMessageType.TRANSFER_OUT) {
                        var cmd = TradingCommandCodec.decodeTransferFunds(payload);
                        long cur = balances.getOrDefault(userId, 0L);
                        if (cur < cmd.amountUnits()) {
                            appliedCommands.remove(commandId);
                            throw new AccountCommandRejectedException("INSUFFICIENT_BALANCE", "INSUFFICIENT_BALANCE");
                        }
                        balances.put(userId, cur - cmd.amountUnits());
                        pendingTransfers.put(cmd.transferId(), cmd);
                    } else if (type == CoreMessageType.TRANSFER_IN) {
                        var cmd = TradingCommandCodec.decodeTransferFunds(payload);
                        long cur = balances.getOrDefault(userId, 0L);
                        balances.put(userId, cur + cmd.amountUnits());
                    } else if (type == CoreMessageType.COMPLETE_TRANSFER) {
                        var cmd = TradingCommandCodec.decodeCompleteTransfer(payload);
                        pendingTransfers.remove(cmd.transferId());
                    }
                }
                if (dropReply.compareAndSet(true, false)) {
                    throw new IllegalStateException("committed reply lost");
                }
                return new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED, CoreResultCode.NONE, seq, seq, new byte[0]);
            });
            when(aeron.query(any(), any(), any())).thenAnswer(call -> {
                CoreMessageType type = call.getArgument(0);
                if (type == CoreMessageType.PENDING_TRANSFER_QUERY) {
                    return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, CoreResultCode.NONE, 0L, 0L,
                            CorePendingTransferCodec.encode(List.copyOf(pendingTransfers.values())));
                }
                return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, CoreResultCode.NONE, 0L, 0L, new byte[0]);
            });
            when(aeron.userState(anyLong())).thenAnswer(call -> {
                long userId = call.getArgument(0);
                long bal = balances.getOrDefault(userId, 0L);
                return new CoreUserStateView(line, userId, 1L, CorePositionMode.ONE_WAY,
                        List.of(new CoreBalanceView("USDT", bal, 0L)), List.of(), List.of(), List.of());
            });
            accounts = new AccountCommandGateway(properties, aeron, mock(BalanceAdjustmentRepository.class));
        }

        long balance() {
            return balances.getOrDefault(42L, 0L);
        }

        void restart() {
            // In-memory state preserved across simulated Core restart.
        }

        @Override
        public void close() {
        }
    }
}
