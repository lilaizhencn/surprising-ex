package com.surprising.aeron.service.orchestration.retention;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.orchestration.result.FundsCommandIndex;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.change.LaneCommitDelta;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.order.RuntimeOrderCommitStateTransitions.TerminalPruneBatch;
import com.surprising.aeron.service.state.risk.LiquidationRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

public final class TerminalStateRetention implements TradingRuntimeState.TerminalOrderSink, AutoCloseable {

    // The live instance is product-owner confined. Snapshot encoding receives detached bytes.

    static final int MAX_TOMBSTONES = 65_536;
    static final int MAX_PRUNE_PER_ACK = 4_096;
    private static final int VERSION = 2;
    private static final int MAX_CLIENT_ID_BYTES = 256;
    private final LinkedHashMap<EntityKey, RetainedEntity> candidates;
    private final TerminalTombstoneStore tombstones;
    private final FundsCommandIndex fundsCommands;
    /** Owner-confined lookup key; candidate keys themselves remain stable Map keys. */
    private final EntityKey candidateLookupKey = new EntityKey(EntityType.ORDER, 1);

    public TerminalStateRetention() {
        this(new LinkedHashMap<>(), new TerminalTombstoneStore(), new FundsCommandIndex());
    }

    private TerminalStateRetention(LinkedHashMap<EntityKey, RetainedEntity> candidates,
                                   TerminalTombstoneStore tombstones,
                                   FundsCommandIndex fundsCommands) {
        this.candidates = candidates;
        this.tombstones = tombstones;
        this.fundsCommands = fundsCommands;
    }

    @Override
    public void accept(long orderId, long userId, String clientOrderId, long coreSequence) {
        tombstones.putIfAbsent(EntityType.ORDER.code, orderId, userId,
                normalizeClientId(clientOrderId), coreSequence);
    }

    @Override
    public void acceptBatch(LaneCommitDelta delta, long coreSequence) {
        for (int index = 0; index < delta.terminalOrderCount(); index++) {
            accept(delta.terminalOrderId(index), delta.terminalOrderUser(index),
                    delta.terminalOrderClient(index), coreSequence);
        }
    }

    @Override
    public void completeSequence() {
        trimTombstones();
    }

    TerminalPruneBatch eligible(TradingCoreState state, long acknowledgedSequence, int limit) {
        if (state == null || acknowledgedSequence < 0 || limit <= 0) {
            throw new IllegalArgumentException("invalid terminal prune request");
        }
        ArrayList<Long> orders = new ArrayList<>();
        ArrayList<Long> algos = new ArrayList<>();
        ArrayList<Long> triggers = new ArrayList<>();
        ArrayList<Long> liquidations = new ArrayList<>();
        int remaining = Math.min(limit, MAX_PRUNE_PER_ACK);
        for (RetainedEntity candidate : candidates.values()) {
            if (remaining == 0) break;
            if (candidate.exportSequence() > acknowledgedSequence || !isStillPrunable(state, candidate)) continue;
            switch (candidate.key().type()) {
                case ORDER -> orders.add(candidate.key().id());
                case ALGO -> algos.add(candidate.key().id());
                case TRIGGER -> triggers.add(candidate.key().id());
                case LIQUIDATION -> liquidations.add(candidate.key().id());
            }
            remaining--;
        }
        return new TerminalPruneBatch(orders, algos, triggers, liquidations);
    }

    TerminalPruneBatch eligible(
            TradingRuntimeState state, long acknowledgedSequence, int limit) {
        if (state == null || acknowledgedSequence < 0 || limit <= 0) {
            throw new IllegalArgumentException("invalid terminal prune request");
        }
        ArrayList<Long> orders = new ArrayList<>();
        ArrayList<Long> algos = new ArrayList<>();
        ArrayList<Long> triggers = new ArrayList<>();
        ArrayList<Long> liquidations = new ArrayList<>();
        int remaining = Math.min(limit, MAX_PRUNE_PER_ACK);
        for (RetainedEntity candidate : candidates.values()) {
            if (remaining == 0) break;
            if (candidate.exportSequence() > acknowledgedSequence || !isStillPrunable(state, candidate)) continue;
            switch (candidate.key().type()) {
                case ORDER -> orders.add(candidate.key().id());
                case ALGO -> algos.add(candidate.key().id());
                case TRIGGER -> triggers.add(candidate.key().id());
                case LIQUIDATION -> liquidations.add(candidate.key().id());
            }
            remaining--;
        }
        return new TerminalPruneBatch(orders, algos, triggers, liquidations);
    }

    void complete(TerminalPruneBatch batch, long acknowledgedSequence) {
        if (batch == null || acknowledgedSequence < 0) {
            throw new IllegalArgumentException("invalid completed terminal prune");
        }
        complete(EntityType.ORDER, batch.orderIds(), acknowledgedSequence);
        complete(EntityType.ALGO, batch.algoOrderIds(), acknowledgedSequence);
        complete(EntityType.TRIGGER, batch.triggerOrderIds(), acknowledgedSequence);
        complete(EntityType.LIQUIDATION, batch.liquidationIds(), acknowledgedSequence);
        trimTombstones();
    }

    private void trimTombstones() {
        tombstones.trim(MAX_TOMBSTONES);
    }

    public boolean containsOrder(long orderId, long userId, String clientOrderId) {
        return contains(EntityType.ORDER, orderId, userId, clientOrderId);
    }

    public boolean orderIdInUse(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("invalid terminal order key");
        candidateLookupKey.reset(EntityType.ORDER, orderId);
        return candidates.containsKey(candidateLookupKey) || tombstones.contains(EntityType.ORDER.code, orderId);
    }

    public boolean containsAlgo(long algoOrderId, long userId, String clientAlgoOrderId) {
        return contains(EntityType.ALGO, algoOrderId, userId, clientAlgoOrderId);
    }

    public boolean containsTrigger(long triggerOrderId, long userId, String clientTriggerOrderId) {
        return contains(EntityType.TRIGGER, triggerOrderId, userId, clientTriggerOrderId);
    }

    public int candidateCount() {
        return candidates.size();
    }

    public int tombstoneCount() {
        return tombstones.size();
    }

    public CommandFingerprint fundsCommand(long msb, long lsb) {
        return fundsCommands.get(msb, lsb);
    }

    public CommandFingerprint fundsCommand(UUID commandId) {
        if (commandId == null) return null;
        return fundsCommand(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    public void retainFundsCommand(long msb, long lsb, CommandFingerprint fingerprint) {
        fundsCommands.put(msb, lsb, fingerprint);
    }

    public void retainFundsCommand(UUID commandId, CommandFingerprint fingerprint) {
        if (commandId == null) throw new IllegalArgumentException("commandId is required");
        retainFundsCommand(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), fingerprint);
    }

    /** One reusable direct-command I/O request; only Ledger accesses its index payload. */
    public static final class FundsIndexWork implements com.surprising.aeron.service.state.ledger.SystemLedgerLane.Entry {
        private TerminalStateRetention retention;
        private TradingRuntimeState completionRuntime;
        private UUID commandId;
        private long commandIdMsb;
        private long commandIdLsb;
        private CommandFingerprint fingerprint, retained;
        private boolean write, prepared, queued;
        private volatile boolean completed = true;

        public void prepareLookup(TerminalStateRetention retention, long msb, long lsb) {
            prepare(retention, null, msb, lsb, null, false);
        }

        public void prepareLookup(TerminalStateRetention retention, UUID commandId) {
            java.util.Objects.requireNonNull(commandId, "commandId");
            prepare(retention, commandId, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), null, false);
        }

        public void prepareWrite(TerminalStateRetention retention, long msb, long lsb, CommandFingerprint fingerprint) {
            prepare(retention, null, msb, lsb, java.util.Objects.requireNonNull(fingerprint), true);
        }

        public void prepareWrite(TerminalStateRetention retention, UUID commandId, CommandFingerprint fingerprint) {
            java.util.Objects.requireNonNull(commandId, "commandId");
            prepare(retention, commandId, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), java.util.Objects.requireNonNull(fingerprint), true);
        }

        private void prepare(TerminalStateRetention retention, UUID commandId, long msb, long lsb,
                             CommandFingerprint fingerprint, boolean write) {
            if (prepared) throw new IllegalStateException("funds index request is still retained");
            this.retention = java.util.Objects.requireNonNull(retention);
            this.commandId = commandId;
            this.commandIdMsb = msb;
            this.commandIdLsb = lsb;
            this.fingerprint = fingerprint;
            this.write = write;
            retained = null;
            queued = false;
            completed = false;
            prepared = true;
        }

        public boolean poll(TradingCoreRuntime owner) {
            owner.assertHealthy();
            if (!prepared) throw new IllegalStateException("funds index request is not prepared");
            if (!owner.runtimeState().systemLedgerStarted()) {
                apply(null);
                return true;
            }
            if (!queued) {
                completionRuntime = owner.runtimeState();
                if (!owner.runtimeState().dispatchLedgerQuery(this)) return false;
                queued = true;
            }
            return completed;
        }

        @Override public void apply(com.surprising.aeron.service.state.ledger.TreasuryRuntime ignored) {
            retention.fundsCommands.bindIoOwner();
            if (write) retention.fundsCommands.put(commandIdMsb, commandIdLsb, fingerprint);
            else retained = retention.fundsCommands.get(commandIdMsb, commandIdLsb);
            TradingRuntimeState completion = completionRuntime;
            completed = true;
            if (completion != null) completion.signalOwnerCompletion();
        }

        public CommandFingerprint retained() {
            if (!completed) throw new IllegalStateException("funds index lookup is incomplete");
            return retained;
        }

        /** Normal completion, or shutdown after the Ledger worker has stopped. */
        public void clear() {
            retention = null; completionRuntime = null; commandId = null;
            commandIdMsb = commandIdLsb = 0;
            fingerprint = retained = null;
            write = prepared = queued = false;
            completed = true;
        }
    }

    public void assertHealthy() { fundsCommands.assertHealthy(); }

    @Override
    public void close() { fundsCommands.close(); }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(VERSION);
            write(output, candidates);
            tombstones.write(output);
            fundsCommands.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encode terminal retention", exception);
        }
    }

    public static TerminalStateRetention decode(byte[] encoded) {
        if (encoded == null) throw new IllegalArgumentException("terminal retention payload is required");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != VERSION) throw new IllegalArgumentException("unsupported terminal retention version");
            LinkedHashMap<EntityKey, RetainedEntity> candidates = read(input, Integer.MAX_VALUE);
            var decodedTombstones = read(input, MAX_TOMBSTONES);
            TerminalTombstoneStore tombstones = new TerminalTombstoneStore();
            for (var value : decodedTombstones.values()) tombstones.put(value.key().type().code,
                    value.key().id(), value.userId(), value.clientId(), value.exportSequence());
            int fundsCommandCount = input.readInt();
            if (fundsCommandCount < 0 || (long) fundsCommandCount * (16 + CommandFingerprint.LENGTH) != input.available()) {
                throw new IllegalArgumentException("invalid funds command retention count");
            }
            FundsCommandIndex fundsCommands = new FundsCommandIndex();
            try {
                for (int index = 0; index < fundsCommandCount; index++) {
                    UUID commandId = new UUID(input.readLong(), input.readLong());
                    byte[] fingerprint = input.readNBytes(CommandFingerprint.LENGTH);
                    if (fingerprint.length != CommandFingerprint.LENGTH
                            || fundsCommands.get(commandId) != null) {
                        throw new IllegalArgumentException("invalid retained funds command");
                    }
                    fundsCommands.put(commandId, CommandFingerprint.fromBytes(fingerprint));
                }
                if (input.available() != 0) throw new IllegalArgumentException("trailing terminal retention bytes");
                return new TerminalStateRetention(candidates, tombstones, fundsCommands);
            } catch (RuntimeException | IOException failure) {
                fundsCommands.close();
                throw failure;
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid terminal retention payload", exception);
        }
    }

    private boolean isStillPrunable(TradingCoreState state, RetainedEntity candidate) {
        return switch (candidate.key().type()) {
            case ORDER -> {
                CoreOrderState order = state.orders().get(candidate.key().id());
                var user = order == null ? null : state.users().get(order.userId());
                var reservation = user == null ? null : user.reservations().get(order.orderId());
                yield order != null && order.status().terminal()
                        && (reservation == null || reservation.remainingUnits() == 0);
            }
            case ALGO -> {
                CoreAlgoOrderState algo = state.algoOrders().get(candidate.key().id());
                yield algo != null && algo.terminal();
            }
            case TRIGGER -> {
                CoreTriggerOrderState trigger = state.triggerOrders().get(candidate.key().id());
                yield trigger != null && !trigger.status().open();
            }
            case LIQUIDATION -> {
                CoreLiquidationState liquidation = state.riskState().liquidations().get(candidate.key().id());
                yield liquidation != null && liquidation.terminal();
            }
        };
    }

    private boolean isStillPrunable(TradingRuntimeState state, RetainedEntity candidate) {
        return switch (candidate.key().type()) {
            case ORDER -> {
                OrderRuntime order = state.order(candidate.key().id());
                ReservationRuntime reservation = order == null ? null : state.reservation(order.orderId());
                yield order != null && order.status().terminal()
                        && (reservation == null || reservation.reservedUnits() == 0);
            }
            case ALGO -> {
                CoreAlgoOrderState algo = state.algoOrder(candidate.key().id());
                yield algo != null && algo.terminal();
            }
            case TRIGGER -> {
                CoreTriggerOrderState trigger = state.triggerOrder(candidate.key().id());
                yield trigger != null && !trigger.status().open();
            }
            case LIQUIDATION -> {
                LiquidationRuntime liquidation = state.liquidation(candidate.key().id());
                yield liquidation != null && (liquidation.status() == CoreLiquidationState.Status.CANCELED
                        || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                        && liquidation.deficitUnits() == 0);
            }
        };
    }

    private void complete(EntityType type, List<Long> ids, long acknowledgedSequence) {
        for (long id : ids) {
            EntityKey key = new EntityKey(type, id);
            RetainedEntity candidate = candidates.remove(key);
            if (candidate == null || candidate.exportSequence() > acknowledgedSequence) {
                throw new IllegalStateException("terminal entity was not eligible for pruning: " + key);
            }
            tombstones.put(type.code, id, candidate.userId(), candidate.clientId(), candidate.exportSequence());
        }
    }

    private boolean contains(EntityType type, long id, long userId, String clientId) {
        if (id <= 0) throw new IllegalArgumentException("invalid terminal entity key");
        if (tombstones.contains(type.code, id)) return true;
        String normalized = normalizeClientId(clientId);
        if (normalized.isEmpty()) return false;
        return tombstones.containsClient(type.code, userId, normalized);
    }

    private static void write(DataOutputStream output, LinkedHashMap<EntityKey, RetainedEntity> values)
            throws IOException {
        output.writeInt(values.size());
        for (RetainedEntity value : values.values()) {
            output.writeByte(value.key().type().code);
            output.writeLong(value.key().id());
            output.writeLong(value.userId());
            output.writeLong(value.exportSequence());
            byte[] clientId = value.clientId().getBytes(StandardCharsets.UTF_8);
            output.writeInt(clientId.length);
            output.write(clientId);
        }
    }

    private static LinkedHashMap<EntityKey, RetainedEntity> read(DataInputStream input, int maximum)
            throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("invalid terminal retention count");
        LinkedHashMap<EntityKey, RetainedEntity> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            int typeCode = input.readUnsignedByte();
            EntityKey key = new EntityKey(EntityType.fromCode(typeCode), input.readLong());
            long userId = input.readLong();
            long exportSequence = input.readLong();
            int textLength = input.readInt();
            if (textLength < 0 || textLength > MAX_CLIENT_ID_BYTES) {
                throw new IllegalArgumentException("invalid terminal client id length");
            }
            byte[] text = input.readNBytes(textLength);
            if (text.length != textLength) throw new IllegalArgumentException("truncated terminal client id");
            RetainedEntity value = new RetainedEntity(key, userId,
                    new String(text, StandardCharsets.UTF_8), exportSequence);
            if (values.put(key, value) != null) throw new IllegalArgumentException("duplicate terminal entity");
        }
        return values;
    }

    private static String normalizeClientId(String clientId) {
        String normalized = clientId == null ? "" : clientId;
        // Any string up to 64 UTF-16 code units fits the 256-byte retention bound,
        // even at four UTF-8 bytes per code point. Avoid rescanning common short IDs;
        // retain the full byte-length validation for larger restored/internal values.
        if (normalized.length() <= 64) return normalized;
        if (com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length(normalized) > MAX_CLIENT_ID_BYTES) {
            throw new IllegalArgumentException("terminal client id is too long");
        }
        return normalized;
    }

    private enum EntityType {
        ORDER(0), ALGO(1), TRIGGER(2), LIQUIDATION(3);
        final int code;
        EntityType(int code) { this.code = code; }
        static EntityType fromCode(int code) {
            return switch (code) {
                case 0 -> ORDER;
                case 1 -> ALGO;
                case 2 -> TRIGGER;
                case 3 -> LIQUIDATION;
                default -> throw new IllegalArgumentException("invalid entity type");
            };
        }
    }

    /** 导出候选的稳定实体键；不用于高频终态FIFO。 */
    private static final class EntityKey {
        private EntityType type;
        private long id;
        private EntityKey(EntityType type, long id) { reset(type, id); }
        private EntityKey reset(EntityType type, long id) {
            if (type == null || id <= 0) throw new IllegalArgumentException("invalid terminal entity key");
            this.type = type; this.id = id; return this;
        }
        EntityType type() { return type; }
        long id() { return id; }
        @Override public int hashCode() { return 31 * type.hashCode() + Long.hashCode(id); }
        @Override public boolean equals(Object other) { return other instanceof EntityKey key && type == key.type && id == key.id; }
    }

    private static final class RetainedEntity {
        private final EntityKey key;
        private final long userId;
        private final String clientId;
        private final long exportSequence;

        private RetainedEntity(EntityKey key, long userId, String clientId, long exportSequence) {
            if (key == null || userId <= 0 || clientId == null || exportSequence <= 0) {
                throw new IllegalArgumentException("invalid retained terminal entity");
            }
            this.key = key;
            this.userId = userId;
            this.clientId = clientId;
            this.exportSequence = exportSequence;
        }

        EntityKey key() { return key; }
        long userId() { return userId; }
        String clientId() { return clientId; }
        long exportSequence() { return exportSequence; }
    }
}
