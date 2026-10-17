package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.SystemLedgerLane;
import com.surprising.aeron.service.state.TreasuryRuntime;
import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/** Active index I/O is Ledger-owned; these cases never create or restore a Cluster snapshot. */
class CoreFundsLedgerIndexTest {
    private static final long TIME = 1_700_000_000_000L;
    private static final String INDEX_DIRECTORY = "surprising.aeron.funds-index-directory";
    private long sequence;

    @ParameterizedTest @EnumSource(ProductLine.class)
    void allFundsKindsRemainIdempotentAfterResultEvictionAcrossProducts(ProductLine product) {
        ProductLine destination = product == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        try (var source = new TradingCoreRuntime(product); var target = new TradingCoreRuntime(destination)) {
            var deposit = command(source, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            applied(source, deposit);
            var transfer = new TransferFundsCommand(511, product, destination,
                    product.accountTypeCode(), destination.accountTypeCode(), "USDT", 250,
                    "ledger-index-511", "资金去重", 7, 22);
            var out = command(source, CoreMessageType.TRANSFER_OUT, 7, TradingCommandCodec.encodeTransferFunds(transfer));
            var in = command(target, CoreMessageType.TRANSFER_IN, 22, TradingCommandCodec.encodeTransferFunds(transfer));
            var complete = command(source, CoreMessageType.COMPLETE_TRANSFER, 7,
                    TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(511)));
            applied(source, out); applied(target, in); applied(source, complete);
            assertOwnerCannotReadOrWriteIndex(source, deposit);
            assertOwnerCannotReadOrWriteIndex(target, in);
            evictOldResults(source, 7); evictOldResults(target, 22);
            for (var request : List.of(deposit, out, complete))
                assertThat(source.resultLedger.get(request.header().commandId())).isNull();
            assertThat(target.resultLedger.get(in.header().commandId())).isNull();

            long sourceCount = source.appliedCommandCount(), targetCount = target.appliedCommandCount();
            long sourceRevision = source.runtimeState.revision(), targetRevision = target.runtimeState.revision();
            long sourceHash = source.stateHash(), targetHash = target.stateHash();
            // These original source sequences are stale: the retained funds identity takes precedence.
            for (var request : List.of(deposit, out, complete)) duplicate(source, request);
            duplicate(target, in);
            assertThat(source.appliedCommandCount()).isEqualTo(sourceCount);
            assertThat(target.appliedCommandCount()).isEqualTo(targetCount);
            assertThat(source.runtimeState.revision()).isEqualTo(sourceRevision);
            assertThat(target.runtimeState.revision()).isEqualTo(targetRevision);
            assertThat(source.stateHash()).isEqualTo(sourceHash);
            assertThat(target.stateHash()).isEqualTo(targetHash);

            var changedPayload = new CoreMessage(deposit.header(), TradingCommandCodec.encodeBalanceAdjustment(
                    new BalanceAdjustmentCommand("USDT", 2_001)));
            assertThat(apply(source, changedPayload).resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            long anotherUser = 8;
            while (source.runtimeState.topology().accountLaneId(anotherUser)
                    == source.runtimeState.topology().accountLaneId(7)) anotherUser++;
            var header = deposit.header();
            var changedUser = new CoreMessage(CoreMessageHeader.command(header.messageType(), header.commandId(),
                    product, header.source(), header.sourceId(), header.sourceSequence(), anotherUser,
                    header.submittedAtEpochMillis(), header.correlationId()), deposit.payloadUnsafe());
            assertThat(apply(source, changedUser).resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            assertThat(source.appliedCommandCount()).isEqualTo(sourceCount);
            assertThat(source.stateHash()).isEqualTo(sourceHash);

            // Equal low halves must not alias different full UUIDs in the persistent index.
            var distinctId = new UUID(header.commandId().getMostSignificantBits() ^ 0x4000L,
                    header.commandId().getLeastSignificantBits());
            applied(source, command(source, CoreMessageType.ADJUST_BALANCE, distinctId, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1))));
            assertThat(source.appliedCommandCount()).isEqualTo(sourceCount + 1);
            assertThat(CoreTestCompletion.userState(source, 7).balances()).singleElement().satisfies(b -> {
                assertThat(b.availableUnits()).isEqualTo(1_751); assertThat(b.lockedUnits()).isZero();
            });
            assertThat(CoreTestCompletion.userState(target, 22).balances()).singleElement()
                    .satisfies(b -> assertThat(b.availableUnits()).isEqualTo(250));
            source.assertClusterCallbackComplete(); target.assertClusterCallbackComplete();
        }
    }

    @Test @Timeout(15)
    void fullLedgerMailboxLeavesOwnerPendingAndAppliesFundsExactlyOnceAfterDrain() {
        try (var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applied(core, command(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100))));
            var gate = new LedgerGate();
            assertThat(core.runtimeState.dispatchLedgerQuery(gate)).isTrue();
            SystemLedgerLane.Entry noop = ignored -> { };
            int offered = 0;
            while (core.runtimeState.dispatchLedgerQuery(noop)) {
                if (++offered > 100_000) throw new AssertionError("Ledger mailbox did not fill");
            }
            long count = core.appliedCommandCount();
            var request = command(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 17)));
            try {
                assertThat(core.apply(request)).isNull();
                assertThat(core.directCommand.active()).isTrue();
                for (int retry = 0; retry < 8; retry++) assertThat(core.directCommand.poll(core)).isNull();
                assertThat(core.appliedCommandCount()).isEqualTo(count);
                assertThat(core.resultLedger.get(request.header().commandId())).isNull();
            } finally { gate.released = true; }
            assertThat(drain(core).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(core.appliedCommandCount()).isEqualTo(count + 1);
            duplicate(core, request);
            assertThat(core.appliedCommandCount()).isEqualTo(count + 1);
            assertThat(CoreTestCompletion.userState(core, 7).balances()).singleElement()
                    .satisfies(b -> assertThat(b.availableUnits()).isEqualTo(117));
            core.assertClusterCallbackComplete();
        }
    }

    @Test void coldFundsIndexHandsExistingIdentitiesToActiveLedger() {
        try (var core = new TradingCoreRuntime(ProductLine.SPOT)) {
            var original = command(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            // Imported old identity metadata is independent of the bounded response cache.
            // This deliberately exercises only the cold index, without running recovery.
            var fingerprint = CommandFingerprint.of(original);
            core.terminalRetention.retainFundsCommand(original.header().commandId(), fingerprint);
            assertThat(core.terminalRetention.fundsCommand(original.header().commandId())).isEqualTo(fingerprint);
            core.activate();
            assertThat(core.resultLedger.get(original.header().commandId())).isNull();
            long count = core.appliedCommandCount();
            long hash = core.stateHash();
            duplicate(core, original);
            assertThat(core.appliedCommandCount()).isEqualTo(count);
            assertThat(core.stateHash()).isEqualTo(hash);
            assertOwnerCannotReadOrWriteIndex(core, original);
            applied(core, command(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1))));
            assertThat(CoreTestCompletion.userState(core, 7).balances()).singleElement()
                    .satisfies(b -> assertThat(b.availableUnits()).isEqualTo(1));
            core.assertClusterCallbackComplete();
        }
    }

    @Test @Timeout(15)
    void indexWriteFailureStopsBeforeTerminalResponseAndDoesNotBecomeBusinessRejection() throws Exception {
        String previous = System.getProperty(INDEX_DIRECTORY);
        Path root = Path.of(previous == null ? System.getProperty("java.io.tmpdir") : previous);
        Files.createDirectories(root);
        Path invalidRoot = Files.createTempFile(root, "funds-index-invalid-root-", ".file");
        var core = new TradingCoreRuntime(ProductLine.SPOT);
        try {
            System.setProperty(INDEX_DIRECTORY, invalidRoot.toString());
            core.activate();
            var request = command(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            assertThat(core.apply(request)).isNull();
            assertThatThrownBy(() -> drain(core)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("funds command index unavailable");
            assertThat(core.appliedCommandCount()).isZero();
            assertThat(core.directCommand.publicationComplete()).isTrue();
            assertThat(core.directCommand.active()).isTrue();
            assertThat(core.resultLedger.get(request.header().commandId())).isNull();
            assertThatThrownBy(core::assertHealthy).isInstanceOf(IllegalStateException.class);
        } finally {
            if (previous == null) System.clearProperty(INDEX_DIRECTORY);
            else System.setProperty(INDEX_DIRECTORY, previous);
            try {
                assertThatThrownBy(core::close).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Core workers stopped with a failure")
                        .hasRootCauseInstanceOf(java.nio.file.FileAlreadyExistsException.class);
            } finally { Files.deleteIfExists(invalidRoot); }
        }
    }

    @Test @Timeout(15)
    void indexCompletionSignalsOwnerEvenWhenTheRequestIsImmediatelyReused() {
        try (var core = new TradingCoreRuntime(ProductLine.SPOT)) {
            core.activate();
            var signals = new java.util.concurrent.atomic.AtomicInteger();
            core.runtimeState.ownerCompletionSignal(signals::incrementAndGet);
            var work = new TerminalStateRetention.FundsIndexWork();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                for (int index = 0; index < 64; index++) {
                    work.prepareLookup(core.terminalRetention, new UUID(998, index));
                    while (!work.poll(core)) {
                        if (System.nanoTime() >= deadline) throw new AssertionError("funds lookup completion timed out");
                        Thread.onSpinWait();
                    }
                    assertThat(work.retained()).isNull();
                    // Completion publishes before the signal: reuse must not clear the worker's signal input.
                    work.clear();
                }
                while (signals.get() != 64) {
                    core.assertHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("funds completion signal lost");
                    Thread.onSpinWait();
                }
                assertThat(core.appliedCommandCount()).isZero();
                core.assertHealthy();
            } finally { core.runtimeState.ownerCompletionSignal(null); }
        }
    }

    private void evictOldResults(TradingCoreRuntime core, long user) {
        for (int revision = 1; revision <= TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS + 1; revision++) {
            var policy = new UpsertFeePolicyCommand(33, revision, user, "", 0, 0, 1, true, TIME - 1, 0);
            applied(core, command(core, CoreMessageType.UPSERT_FEE_POLICY, user, TradingCommandCodec.encodeUpsertFeePolicy(policy)));
        }
    }

    private void assertOwnerCannotReadOrWriteIndex(TradingCoreRuntime core, CoreMessage request) {
        assertThatThrownBy(() -> core.terminalRetention.fundsCommand(request.header().commandId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SystemLedgerLane");
        assertThatThrownBy(() -> core.terminalRetention.retainFundsCommand(request.header().commandId(),
                CommandFingerprint.of(request))).isInstanceOf(IllegalStateException.class).hasMessageContaining("SystemLedgerLane");
    }

    private void applied(TradingCoreRuntime core, CoreMessage request) {
        assertThat(apply(core, request).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }

    private void duplicate(TradingCoreRuntime core, CoreMessage request) {
        var result = apply(core, request);
        assertThat(result.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(result.resultCode()).isEqualTo(CoreResultCode.NONE);
    }

    private CoreResponse apply(TradingCoreRuntime core, CoreMessage request) {
        return CoreTestCompletion.applyAsynchronously(core, request);
    }

    private CoreMessage command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        return command(core, type, new UUID(997, sequence + 1), user, payload);
    }

    private CoreMessage command(TradingCoreRuntime core, CoreMessageType type, UUID id, long user, byte[] payload) {
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, id, core.productLine,
                CommandSource.OPERATIONS, 997, next, user, TIME, next), payload);
    }

    private CoreResponse drain(TradingCoreRuntime core) {
        CoreResponse result = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (core.directCommand.active()) {
            result = core.directCommand.poll(core);
            if (System.nanoTime() >= deadline) throw new AssertionError("funds direct command timed out");
            Thread.onSpinWait();
        }
        return result;
    }

    private static final class LedgerGate implements SystemLedgerLane.Entry {
        private volatile boolean released;
        @Override public boolean ready() { return released; }
        @Override public void apply(TreasuryRuntime ignored) { }
    }
}
