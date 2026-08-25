package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreReservationView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreRiskQueryCodec;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreTreasuryAssetView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterFundsReconcileTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void streamsMillionUserRangeWithoutMaterializingIdsAndAcceptsExtremeId() {
        var ranges = FundsReconciliation.UserRanges.parse("1:1000001,9223372036854775806:9223372036854775807");

        assertThat(ranges.size()).isEqualTo(1_000_001L);
        assertThat(ranges.stream().limit(3)).containsExactly(1L, 2L, 3L);
        assertThat(ranges.stream().skip(1_000_000L).findFirst()).hasValue(Long.MAX_VALUE - 1);
    }

    @Test
    void rejectsMalformedAndOverlappingRanges() {
        assertThatThrownBy(() -> FundsReconciliation.UserRanges.parse("1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("range");
        assertThatThrownBy(() -> FundsReconciliation.UserRanges.parse("1:4,3:5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overlap");
        assertThatThrownBy(() -> config("1:3", "2:4", ledger(baseLedger())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user/maker overlap");
    }

    @Test
    void rejectsMalformedLedgerAndArithmeticOverflow() {
        assertThatThrownBy(() -> ledger(List.of("SEED\tUSER\t1\tUSDT")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 1");
        assertThatThrownBy(() -> ledger(List.of(
                row("SEED", "USER", 1, "USDT", "-", "AVAILABLE", Long.MAX_VALUE),
                row("OPERATION", "USER", 1, "USDT", "-", "AVAILABLE", 1))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void supportsEveryProductLineAndRejectsActualAmountOverflowAndUnbalancedFlow() {
        for (ProductLine productLine : ProductLine.values()) {
            FundsReconciliation.Config config = new FundsReconciliation.Config(productLine,
                    FundsReconciliation.UserRanges.parse("1:2"), FundsReconciliation.UserRanges.parse(""),
                    ledger(baseLedger()), 100, 1_000, null);
            assertThat(config.productLine()).isEqualTo(productLine);
        }

        List<String> overflowingLedger = List.of(
                row("SEED", "USER", 1, "USDT", "-", "AVAILABLE", Long.MAX_VALUE),
                row("SEED", "USER", 1, "USDT", "-", "LOCKED", 0),
                row("SEED", "USER", 2, "USDT", "-", "AVAILABLE", 1),
                row("SEED", "USER", 2, "USDT", "-", "LOCKED", 0));
        FakeGateway overflowing = new FakeGateway(Map.of(
                1L, user(1, List.of(new CoreBalanceView("USDT", Long.MAX_VALUE, 0))),
                2L, user(2, List.of(new CoreBalanceView("USDT", 1, 0)))), List.of(), 7);
        assertThatThrownBy(() -> FundsReconciliation.reconcile(
                config("1:3", "", ledger(overflowingLedger)), overflowing))
                .isInstanceOf(ArithmeticException.class);

        List<String> unbalancedLedger = new ArrayList<>(baseLedger());
        unbalancedLedger.add(row("OPERATION", "USER", 1, "USDT", "-", "FEE", -1));
        FakeGateway unbalanced = new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), List.of(), 7);
        assertThatThrownBy(() -> FundsReconciliation.reconcile(
                config("1:2", "", ledger(unbalancedLedger)), unbalanced))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not conserved");
    }

    @Test
    void hardFailsOnUnexpectedAssetAndOneUnitDifference() {
        FakeGateway unexpected = new FakeGateway(Map.of(1L, user(1, List.of(
                new CoreBalanceView("USDT", 100, 0), new CoreBalanceView("BTC", 1, 0)))), List.of(), 7);
        assertThatThrownBy(() -> FundsReconciliation.reconcile(config("1:2", "", ledger(baseLedger())), unexpected))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unexpected assets").hasMessageContaining("BTC");

        FakeGateway oneUnit = new FakeGateway(Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 99, 0)))),
                List.of(), 7);
        assertThatThrownBy(() -> FundsReconciliation.reconcile(config("1:2", "", ledger(baseLedger())), oneUnit))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("difference=-1");
    }

    @Test
    void hardFailsWhenZeroValuedStateKeySetsDiffer() {
        FakeGateway gateway = new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), List.of(), 7);

        assertThatThrownBy(() -> FundsReconciliation.reconcile(
                config("1:2", "", ledger(List.of(
                        row("SEED", "USER", 1, "USDT", "-", "AVAILABLE", 100)))), gateway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("state key set mismatch")
                .hasMessageContaining("LOCKED");
    }

    @Test
    void checksEveryQueryStatusIncludingRiskAndLiquidation() {
        FakeGateway riskFailure = new FakeGateway(Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))),
                List.of(), 7);
        riskFailure.rejectType = CoreMessageType.RISK_STATE_QUERY;
        assertThatThrownBy(() -> FundsReconciliation.reconcile(config("1:2", "", ledger(baseLedger())), riskFailure))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RISK_STATE_QUERY");

        FakeGateway liquidationFailure = new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), List.of(), 7);
        liquidationFailure.rejectType = CoreMessageType.LIQUIDATION_WORK_QUERY;
        assertThatThrownBy(() -> FundsReconciliation.reconcile(config("1:2", "", ledger(baseLedger())),
                liquidationFailure)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIQUIDATION_WORK_QUERY");
    }

    @Test
    void traversesPage101AndFailsOnItsOneUnitDiscrepancy() {
        List<CoreLiquidationWorkView.Resolution> resolutions = resolutions(101);
        FakeGateway gateway = new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), resolutions, 11);
        List<String> lines = new ArrayList<>(baseLedger());
        lines.add(row("OPERATION", "TREASURY", 0, "USDT", "-", "LIQUIDATION_INSURANCE", 100));

        assertThatThrownBy(() -> FundsReconciliation.reconcile(
                config("1:2", "", ledger(lines), 1, 1_000, null), gateway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIQUIDATION_INSURANCE").hasMessageContaining("difference=1");
        assertThat(gateway.liquidationQueries).isEqualTo(102);
    }

    @Test
    void happyPathTraversesMoreThanOneHundredPagesAndProducesStableHashes() {
        List<CoreLiquidationWorkView.Resolution> resolutions = resolutions(101);
        List<String> lines = new ArrayList<>(baseLedger());
        lines.add(row("OPERATION", "TREASURY", 0, "USDT", "-", "LIQUIDATION_INSURANCE", 101));
        FundsReconciliation.Config config = config("1:2", "", ledger(lines), 1, 1_000, null);

        FakeGateway firstGateway = new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), resolutions, 17);
        FakeGateway secondGateway = new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), resolutions, 17);
        FundsReconciliation.Result first = FundsReconciliation.reconcile(config, firstGateway);
        FundsReconciliation.Result second = FundsReconciliation.reconcile(config, secondGateway);

        assertThat(first.fundsDifference()).isZero();
        assertThat(first.stateHash()).isEqualTo(second.stateHash()).hasSize(64);
        assertThat(first.fundsHash()).isEqualTo(second.fundsHash()).hasSize(64);
        assertThat(first.coreStateHash()).isEqualTo(17);
        assertThat(first.liquidationPages()).isEqualTo(102);
        assertThat(firstGateway.liquidationQueries).isEqualTo(102);
    }

    @Test
    void reconcilesInsuranceAndAdlResolutionPagesExactly() {
        List<String> lines = new ArrayList<>(baseLedger());
        lines.add(row("OPERATION", "TREASURY", 0, "USDT", "-", "LIQUIDATION_INSURANCE", 2));
        lines.add(row("OPERATION", "TREASURY", 0, "USDT", "-", "LIQUIDATION_ADL", 3));
        FakeGateway gateway = new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), resolutions(2), 19);
        gateway.adlResolutions = resolutions(3, CoreLiquidationWorkView.Purpose.ADL);

        FundsReconciliation.Result result = FundsReconciliation.reconcile(
                config("1:2", "", ledger(lines)), gateway);

        assertThat(result.fundsDifference()).isZero();
        assertThat(result.liquidationPages()).isEqualTo(2);
    }

    @Test
    void stateHashIsIndependentOfLiquidationPageSize() {
        List<CoreLiquidationWorkView.Resolution> resolutions = resolutions(101);
        List<String> lines = new ArrayList<>(baseLedger());
        lines.add(row("OPERATION", "TREASURY", 0, "USDT", "-", "LIQUIDATION_INSURANCE", 101));

        FundsReconciliation.Result onePerPage = FundsReconciliation.reconcile(
                config("1:2", "", ledger(lines), 1, 1_000, null),
                new FakeGateway(Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))),
                        resolutions, 17));
        FundsReconciliation.Result twentyPerPage = FundsReconciliation.reconcile(
                config("1:2", "", ledger(lines), 20, 1_000, null),
                new FakeGateway(Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))),
                        resolutions, 17));

        assertThat(onePerPage.stateHash()).isEqualTo(twentyPerPage.stateHash());
        assertThat(onePerPage.fundsHash()).isEqualTo(twentyPerPage.fundsHash());
    }

    @Test
    void comparesReservationPositionRealizedPnlTreasuryAndFlowConservationExactly() {
        CoreUserStateView state = new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 1, 9,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 80, 20)),
                List.of(new CoreReservationView(91, "BTC-USDT", 1, ReservationKind.DERIVATIVE_MARGIN,
                        "USDT", 20, 3, 2, 5)),
                List.of(new CorePositionView("BTC-USDT", "USDT", CoreMarginMode.CROSS, CorePositionSide.NET,
                        1, 5, 10, 50, 7, 15)), List.of());
        List<String> lines = List.of(
                row("SEED", "USER", 1, "USDT", "-", "AVAILABLE", 80),
                row("SEED", "USER", 1, "USDT", "-", "LOCKED", 20),
                row("SEED", "USER", 1, "USDT", "-", "RESERVATION", 15),
                row("OPERATION", "USER", 1, "USDT", "BTC-USDT", "POSITION_QUANTITY", 5),
                row("OPERATION", "USER", 1, "USDT", "BTC-USDT", "POSITION_MARGIN", 15),
                row("OPERATION", "USER", 1, "USDT", "BTC-USDT", "REALIZED_PNL", 7),
                row("OPERATION", "USER", 1, "USDT", "-", "FEE", -2),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "FEE", 2),
                row("OPERATION", "USER", 1, "USDT", "-", "FUNDING", -3),
                row("OPERATION", "MAKER", 2, "USDT", "-", "FUNDING", 3),
                row("OPERATION", "USER", 1, "USDT", "-", "LIQUIDATION", -5),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "LIQUIDATION", 5),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "INSURANCE", -6),
                row("OPERATION", "USER", 1, "USDT", "-", "INSURANCE", 6),
                row("OPERATION", "USER", 1, "USDT", "-", "ADL", -7),
                row("OPERATION", "MAKER", 2, "USDT", "-", "ADL", 7),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "TREASURY_FEES", 4),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "TREASURY_INSURANCE", 9),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "TREASURY_DEFICIT", 12),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "TREASURY_LIQUIDATION_FEES", 0),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "TREASURY_FUNDING_RESIDUAL", 0),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "TREASURY_ROUNDING_RESIDUAL", 0),
                row("OPERATION", "TREASURY", 0, "USDT", "-", "TREASURY_CLEARING_PNL", 0));
        CoreUserStateView maker = user(2, List.of());
        FakeGateway gateway = new FakeGateway(Map.of(1L, state, 2L, maker), List.of(), 23);
        gateway.treasury = List.of(new CoreTreasuryAssetView("USDT", 4, 9, 12));

        FundsReconciliation.Result result = FundsReconciliation.reconcile(
                config("1:2", "2:3", ledger(lines)), gateway);

        assertThat(result.fundsDifference()).isZero();
        assertThat(result.userCount()).isEqualTo(1);
        assertThat(result.makerCount()).isEqualTo(1);
    }

    @Test
    void resumesAfterRepeatedInterruptionsWithoutRequeryingCompletedUsers() {
        List<String> lines = new ArrayList<>();
        Map<Long, CoreUserStateView> users = new HashMap<>();
        for (long userId = 1; userId <= 5; userId++) {
            lines.add(row("SEED", "USER", userId, "USDT", "-", "AVAILABLE", 10));
            lines.add(row("SEED", "USER", userId, "USDT", "-", "LOCKED", 0));
            users.put(userId, user(userId, List.of(new CoreBalanceView("USDT", 10, 0))));
        }
        Path checkpoint = temporaryDirectory.resolve("resume.properties");
        FundsReconciliation.Config config = config("1:6", "", ledger(lines), 100, 1_000, checkpoint);
        FakeGateway interrupted = new FakeGateway(users, List.of(), 31);
        interrupted.interruptAtUserQuery = 3;

        assertThatThrownBy(() -> FundsReconciliation.reconcile(config, interrupted))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("injected interruption");
        FakeGateway interruptedAgain = new FakeGateway(users, List.of(), 31);
        interruptedAgain.interruptAtUserQuery = 2;
        assertThatThrownBy(() -> FundsReconciliation.reconcile(config, interruptedAgain))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("injected interruption");
        assertThat(interruptedAgain.userQueries).containsExactly(3L, 4L);

        FakeGateway resumed = new FakeGateway(users, List.of(), 31);
        FundsReconciliation.Result resumedResult = FundsReconciliation.reconcile(config, resumed);
        FundsReconciliation.Result uninterrupted = FundsReconciliation.reconcile(
                config("1:6", "", ledger(lines)), new FakeGateway(users, List.of(), 31));

        assertThat(resumed.userQueries).containsExactly(4L, 5L);
        assertThat(resumedResult.stateHash()).isEqualTo(uninterrupted.stateHash());
        assertThat(resumedResult.fundsHash()).isEqualTo(uninterrupted.fundsHash());
    }

    @Test
    void rejectsStaleCheckpointCoreHashAndNonAdvancingLiquidationCursor() {
        List<String> lines = new ArrayList<>();
        for (long userId = 1; userId <= 3; userId++) {
            lines.add(row("SEED", "USER", userId, "USDT", "-", "AVAILABLE", 10));
            lines.add(row("SEED", "USER", userId, "USDT", "-", "LOCKED", 0));
        }
        Map<Long, CoreUserStateView> users = Map.of(
                1L, user(1, List.of(new CoreBalanceView("USDT", 10, 0))),
                2L, user(2, List.of(new CoreBalanceView("USDT", 10, 0))),
                3L, user(3, List.of(new CoreBalanceView("USDT", 10, 0))));
        Path checkpoint = temporaryDirectory.resolve("stale.properties");
        FundsReconciliation.Config config = config("1:4", "", ledger(lines), 100, 1_000, checkpoint);
        FakeGateway interrupted = new FakeGateway(users, List.of(), 41);
        interrupted.interruptAtUserQuery = 2;
        assertThatThrownBy(() -> FundsReconciliation.reconcile(config, interrupted))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> FundsReconciliation.reconcile(config, new FakeGateway(users, List.of(), 42)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Core state hash changed");

        FakeGateway stuck = new FakeGateway(Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))),
                resolutions(2), 7);
        stuck.nonAdvancingCursor = true;
        assertThatThrownBy(() -> FundsReconciliation.reconcile(config("1:2", "", ledger(baseLedger()), 1, 5, null), stuck))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cursor did not advance");

        FakeGateway bounded = new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), resolutions(2), 7);
        assertThatThrownBy(() -> FundsReconciliation.reconcile(
                config("1:2", "", ledger(baseLedger()), 1, 1, null), bounded))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max pages=1");
        assertThat(bounded.liquidationQueries).isEqualTo(1);
    }

    @Test
    void completedCheckpointCannotReturnMisleadingPassForStaleCoreState() {
        Path checkpoint = temporaryDirectory.resolve("completed.properties");
        List<String> ledger = List.of(
                row("SEED", "USER", 1, "USDT", "-", "AVAILABLE", 100),
                row("SEED", "USER", 1, "USDT", "-", "LOCKED", 0));
        FundsReconciliation.Config config = config("1:2", "", ledger(ledger), 100, 1_000, checkpoint);
        FundsReconciliation.reconcile(config, new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), List.of(), 51));
        FakeGateway stale = new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 99, 0)))), List.of(), 52);

        assertThatThrownBy(() -> FundsReconciliation.reconcile(config, stale))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("difference=-1");
        assertThat(stale.userQueries).containsExactly(1L);
    }

    @Test
    void rejectsCompletedCheckpointWhenLedgerChanges() {
        Path checkpoint = temporaryDirectory.resolve("stale-ledger.properties");
        FundsReconciliation.Config initial = config("1:2", "", ledger(baseLedger()), 100, 1_000, checkpoint);
        FundsReconciliation.reconcile(initial, new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 100, 0)))), List.of(), 61));
        FundsReconciliation.Config changed = config("1:2", "", ledger(List.of(
                row("SEED", "USER", 1, "USDT", "-", "AVAILABLE", 99),
                row("SEED", "USER", 1, "USDT", "-", "LOCKED", 0))), 100, 1_000, checkpoint);

        assertThatThrownBy(() -> FundsReconciliation.reconcile(changed, new FakeGateway(
                Map.of(1L, user(1, List.of(new CoreBalanceView("USDT", 99, 0)))), List.of(), 62)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint configuration changed");
    }

    private FundsReconciliation.Config config(String users, String makers, FundsReconciliation.Ledger ledger) {
        return config(users, makers, ledger, 100, 10_000, null);
    }

    private FundsReconciliation.Config config(String users, String makers, FundsReconciliation.Ledger ledger,
                                               int pageSize, int maxPages, Path checkpoint) {
        return new FundsReconciliation.Config(ProductLine.LINEAR_PERPETUAL,
                FundsReconciliation.UserRanges.parse(users), FundsReconciliation.UserRanges.parse(makers),
                ledger, pageSize, maxPages, checkpoint);
    }

    private static FundsReconciliation.Ledger ledger(List<String> lines) {
        return FundsReconciliation.Ledger.parse(lines);
    }

    private static List<String> baseLedger() {
        return List.of(
                row("SEED", "USER", 1, "USDT", "-", "AVAILABLE", 100),
                row("SEED", "USER", 1, "USDT", "-", "LOCKED", 0));
    }

    private static String row(String kind, String role, long userId, String asset, String symbol,
                              String metric, long delta) {
        return String.join("\t", kind, role, Long.toString(userId), asset, symbol, metric, Long.toString(delta));
    }

    private static CoreUserStateView user(long userId, List<CoreBalanceView> balances) {
        return new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, userId, 1,
                CorePositionMode.ONE_WAY, balances, List.of(), List.of(), List.of());
    }

    private static List<CoreLiquidationWorkView.Resolution> resolutions(int count) {
        return resolutions(count, CoreLiquidationWorkView.Purpose.INSURANCE);
    }

    private static List<CoreLiquidationWorkView.Resolution> resolutions(
            int count, CoreLiquidationWorkView.Purpose purpose) {
        List<CoreLiquidationWorkView.Resolution> values = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            values.add(new CoreLiquidationWorkView.Resolution(index, 1, "BTC-USDT", "USDT",
                    CoreMarginMode.CROSS, CorePositionSide.NET, 1, 1, 1, 1,
                    purpose));
        }
        return values;
    }

    private static final class FakeGateway implements FundsReconciliation.QueryGateway {
        private final Map<Long, CoreUserStateView> users;
        private final List<CoreLiquidationWorkView.Resolution> resolutions;
        private final long stateHash;
        private final List<Long> userQueries = new ArrayList<>();
        private List<CoreTreasuryAssetView> treasury = List.of();
        private CoreMessageType rejectType;
        private int interruptAtUserQuery;
        private int liquidationQueries;
        private boolean nonAdvancingCursor;
        private List<CoreLiquidationWorkView.Resolution> adlResolutions = List.of();

        private FakeGateway(Map<Long, CoreUserStateView> users,
                            List<CoreLiquidationWorkView.Resolution> resolutions,
                            long stateHash) {
            this.users = users;
            this.resolutions = resolutions;
            this.stateHash = stateHash;
        }

        @Override
        public CoreResponse query(CoreMessageType type, long userId, byte[] payload) {
            if (type == rejectType) {
                return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.NONE, 0, stateHash, new byte[0]);
            }
            return switch (type) {
                case USER_STATE_QUERY -> userResponse(userId);
                case RISK_STATE_QUERY -> ok(CoreRiskQueryCodec.encode(List.of()));
                case TREASURY_STATE_QUERY -> ok(CoreStateQueryCodec.encodeTreasuryState(treasury));
                case LIQUIDATION_WORK_QUERY -> liquidationResponse(payload);
                default -> throw new AssertionError("unexpected query " + type);
            };
        }

        private CoreResponse userResponse(long userId) {
            userQueries.add(userId);
            if (interruptAtUserQuery > 0 && userQueries.size() == interruptAtUserQuery) {
                throw new IllegalStateException("injected interruption");
            }
            CoreUserStateView state = users.get(userId);
            if (state == null) throw new AssertionError("missing fake user " + userId);
            return ok(CoreStateQueryCodec.encodeUserState(state));
        }

        private CoreResponse liquidationResponse(byte[] payload) {
            liquidationQueries++;
            CoreLiquidationWorkView.Query query = CoreLiquidationWorkCodec.decodeQuery(payload);
            List<CoreLiquidationWorkView.Resolution> selected =
                    query.purpose() == CoreLiquidationWorkView.Purpose.ADL ? adlResolutions : resolutions;
            int start = Math.toIntExact(query.afterLiquidationId());
            if (start >= selected.size()) {
                return ok(CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                        ProductLine.LINEAR_PERPETUAL, query.afterLiquidationId(), true, null,
                        List.of(), List.of())));
            }
            int end = Math.min(start + query.maxItems(), selected.size());
            long cursor = nonAdvancingCursor ? query.afterLiquidationId() : end;
            return ok(CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                    ProductLine.LINEAR_PERPETUAL, cursor, end == selected.size(), null,
                    List.of(), selected.subList(start, end))));
        }

        private CoreResponse ok(byte[] data) {
            return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK,
                    CoreResultCode.NONE, 0, stateHash, data);
        }
    }
}
