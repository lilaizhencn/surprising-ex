package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.*;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.AccountCommandService;
import com.surprising.aeron.service.account.UserAccountSnapshot;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.snapshot.state.SnapshotLoader;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class AccountControlTaskResultTest {
    /** 同一续步跨真实 Lane 反复使用，检查布尔结果、超出装箱缓存的资产编号及 Owner 发布时机。 */
    @ParameterizedTest @ValueSource(ints = {1, 4})
    void reusedTaskPublishesOnlyAfterReceiptAndRetainsPrimitiveResults(int lanes) {
        var product = ProductLine.LINEAR_PERPETUAL;
        var instrument = CoreInstrument.from(product, new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT",
                1, 1, 1, new CoreInstrumentRiskLimits(100_000, 10_000_000, Long.MAX_VALUE, 0, Long.MAX_VALUE,
                List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, 10_000_000, 100_000, 50_000))), 0, 0, 0, -1, 0));
        var position = new CorePositionState("1", "USDT", CoreMarginMode.ISOLATED, CorePositionSide.NET,
                10, 100, 1000, 0, 100);
        var user = new UserAccountSnapshot(product, 7, 1, Map.of("USDT", new AssetBalance("USDT", 900, 100)),
                Map.of(), Map.of("1", position), com.surprising.aeron.protocol.CorePositionMode.ONE_WAY);
        var initial = new TradingCoreState(product, 1, Map.of(7L, user), Map.of(), Map.of("1", instrument),
                CoreRiskState.empty(), CoreTreasuryState.empty(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        var ids = new IdIndex();
        for (int i = 0; i < 256; i++) ids.assetId("ASSET" + i);
        try (var accountBook = SnapshotLoader.loadSnapshot(initial, ids,
                new LaneTopology(1, lanes, 42, 32, 64, 128))) {
            assertThat(ids.assetId("USDT")).isGreaterThan(127);
            accountBook.startAccountLanes();
            var task = new AccountCommandService();
            var exceedsAvailable = new TransferFundsCommand(91, product, product,
                    product.accountTypeCode(), product.accountTypeCode(), "USDT", 901, "frozen-test", "", 7, 8);
            assertThatThrownBy(() -> LedgerControlTestSupport.await(accountBook,
                    task.prepareTransferOut(accountBook, ids, 7, exceedsAvailable)))
                    .hasMessageContaining("insufficient");
            assertThat(accountBook.pendingTransferUser(91)).isZero();
            assertThat(LedgerControlTestSupport.commit(accountBook, ids, false)).isEqualTo(initial);
            for (long delta : new long[]{10, -10}) {
                task.preparePositionMarginAdjustment(accountBook, ids, 7,
                        new AdjustPositionMarginCommand("1", CorePositionSide.NET, delta));
                assertThat(task.stateChanged()).isFalse();
                LedgerControlTestSupport.await(accountBook, task);
                assertThat(task.stateChanged()).isTrue();
                assertThat(accountBook.controlLaneResult(accountBook.topology().accountLaneId(7))).isNull();
                long revision = accountBook.revision();
                assertThat(task.getAsBoolean()).isTrue();
                assertThat(accountBook.revision()).isEqualTo(revision);
                var committed = LedgerControlTestSupport.commit(accountBook, ids, false);
                assertThat(committed.users().get(7L).balances().get("USDT"))
                        .isEqualTo(new AssetBalance("USDT", delta > 0 ? 890 : 900, delta > 0 ? 110 : 100));
                assertThat(committed.users().get(7L).positions().get("1").positionMarginUnits())
                        .isEqualTo(delta > 0 ? 110 : 100);
            }
            var leverage = new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 5_000_000);
            var mode = new UpdatePositionModeCommand(CorePositionMode.HEDGE);
            for (boolean changed : new boolean[]{true, false}) {
                LedgerControlTestSupport.await(accountBook, task.prepareLeverageChange(accountBook, ids, 9, leverage));
                assertThat(task.stateChanged()).isEqualTo(changed);
                assertThat(accountBook.controlLaneResult(accountBook.topology().accountLaneId(9))).isNull();
                var committed = LedgerControlTestSupport.commit(accountBook, ids, false);
                assertThat(committed.leverages()).containsEntry(new CoreLeverageKey(9, "1", CoreMarginMode.CROSS), 5_000_000L);
                LedgerControlTestSupport.await(accountBook, task.preparePositionModeChange(accountBook, 9, mode));
                assertThat(task.stateChanged()).isEqualTo(changed);
                assertThat(accountBook.controlLaneResult(accountBook.topology().accountLaneId(9))).isNull();
                assertThat(LedgerControlTestSupport.commit(accountBook, ids, false).users().get(9L).positionMode())
                        .isEqualTo(CorePositionMode.HEDGE);
            }
        }
    }
}
