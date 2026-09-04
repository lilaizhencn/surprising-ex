package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.service.state.CoreAlgoOrderState;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreTriggerOrderState;
import com.surprising.aeron.service.state.LiquidationRuntime;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.ReservationRuntime;
import com.surprising.aeron.service.state.RuntimeFactFrame;
import com.surprising.aeron.service.state.TerminalPruneBatch;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TerminalStateRetention implements RuntimeFactFrame.RetentionConsumer {

    // The live instance is product-owner confined. Snapshot encoding receives a detached copy.

    static final int MAX_TOMBSTONES = 65_536;
    static final int MAX_PRUNE_PER_ACK = 4_096;
    static final int MAX_FUNDS_COMMANDS = 131_072;
    private static final int VERSION = 2;
    private static final int MAX_CLIENT_ID_BYTES = 256;
    private final LinkedHashMap<EntityKey, RetainedEntity> candidates;
    private final LinkedHashMap<EntityKey, RetainedEntity> tombstones;
    private final Map<ClientIdentity, EntityKey> tombstonesByClient;
    private final LinkedHashMap<UUID, CommandFingerprint> fundsCommands;
    private long visitingExportSequence;
    private final ArrayList<Long> sortedScratch = new ArrayList<>();
    private long candidateDigest;
    private long tombstoneDigest;
    private long fundsCommandDigest;

    TerminalStateRetention() {
        this(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private TerminalStateRetention(LinkedHashMap<EntityKey, RetainedEntity> candidates,
                                   LinkedHashMap<EntityKey, RetainedEntity> tombstones,
                                   LinkedHashMap<UUID, CommandFingerprint> fundsCommands) {
        this(candidates, tombstones, fundsCommands,
                entityDigest(candidates), entityDigest(tombstones), fundsCommandDigest(fundsCommands));
    }

    private TerminalStateRetention(LinkedHashMap<EntityKey, RetainedEntity> candidates,
                                   LinkedHashMap<EntityKey, RetainedEntity> tombstones,
                                   LinkedHashMap<UUID, CommandFingerprint> fundsCommands,
                                   long candidateDigest, long tombstoneDigest, long fundsCommandDigest) {
        this.candidates = candidates;
        this.tombstones = tombstones;
        this.fundsCommands = fundsCommands;
        this.candidateDigest = candidateDigest;
        this.tombstoneDigest = tombstoneDigest;
        this.fundsCommandDigest = fundsCommandDigest;
        this.tombstonesByClient = new HashMap<>();
        tombstones.values().forEach(this::indexTombstone);
    }

    void observe(TradingCoreState after, long exportSequence,
                 Iterable<Long> orderIds, Iterable<Long> liquidationIds, Iterable<Long> triggerOrderIds) {
        if (after == null || exportSequence <= 0) {
            throw new IllegalArgumentException("invalid terminal retention observation");
        }
        forEachSorted(orderIds, id -> observeOrder(after.orders().get(id), exportSequence));
        forEachSorted(after.changedAlgoOrderIds(), id -> observeAlgo(after.algoOrders().get(id), exportSequence));
        forEachSorted(triggerOrderIds, id ->
                observeTrigger(after.triggerOrders().get(id), exportSequence));
        forEachSorted(liquidationIds, id ->
                observeLiquidation(after.riskState().liquidations().get(id), exportSequence));
    }

    void observe(List<RuntimeFactFrame> patches, long exportSequence) {
        if (patches == null || patches.isEmpty() || exportSequence <= 0) {
            throw new IllegalArgumentException("invalid terminal retention patch observation");
        }
        for (RuntimeFactFrame patch : patches) {
            for (RuntimeFactFrame.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
                group.orders().forEach(change -> observeOrder(change.after(), exportSequence));
                group.algoOrders().forEach(change -> observeAlgo(change.after(), exportSequence));
                group.triggerOrders().forEach(change -> observeTrigger(change.after(), exportSequence));
                group.liquidations().forEach(change -> observeLiquidation(change.after(), exportSequence));
            }
        }
    }

    void observe(RuntimeFactFrame patch, long exportSequence) {
        if (patch == null || exportSequence <= 0) {
            throw new IllegalArgumentException("invalid terminal retention patch observation");
        }
        for (RuntimeFactFrame.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            group.orders().forEach(change -> observeOrder(change.after(), exportSequence));
            group.algoOrders().forEach(change -> observeAlgo(change.after(), exportSequence));
            group.triggerOrders().forEach(change -> observeTrigger(change.after(), exportSequence));
            group.liquidations().forEach(change -> observeLiquidation(change.after(), exportSequence));
        }
    }

    @Override
    public void order(OrderRuntime value) { observeOrder(value, visitingExportSequence); }

    @Override
    public void liquidation(LiquidationRuntime value) { observeLiquidation(value, visitingExportSequence); }

    @Override
    public void algoOrder(CoreAlgoOrderState value) { observeAlgo(value, visitingExportSequence); }

    @Override
    public void triggerOrder(CoreTriggerOrderState value) { observeTrigger(value, visitingExportSequence); }

    void observeAcknowledgedOrders(
            TradingCoreState state, long acknowledgedSequence, Iterable<Long> orderIds) {
        if (state == null || acknowledgedSequence <= 0 || orderIds == null) {
            throw new IllegalArgumentException("invalid acknowledged terminal orders");
        }
        forEachSorted(orderIds, id -> observeOrder(state.orders().get(id), acknowledgedSequence));
    }

    void retainPrunedOrders(TradingRuntimeState state, long coreSequence) {
        if (state == null || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid pruned terminal order retention");
        }
        visitingExportSequence = coreSequence;
        try {
            state.acceptChangedTerminalOrders(this::retainPrunedOrder);
        } finally {
            visitingExportSequence = 0;
        }
        trimTombstones();
    }

    private void retainPrunedOrder(OrderRuntime order) {
        EntityKey key = new EntityKey(EntityType.ORDER, order.orderId());
        if (tombstones.containsKey(key)) return;
        RetainedEntity retained = new RetainedEntity(key, order.userId(),
                normalizeClientId(order.clientOrderId()), visitingExportSequence);
        RetainedEntity previous = tombstones.put(key, retained);
        if (previous != null) tombstoneDigest ^= entryDigest(previous);
        tombstoneDigest ^= entryDigest(retained);
        indexTombstone(retained);
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
        while (tombstones.size() > MAX_TOMBSTONES) {
            EntityKey key = tombstones.keySet().iterator().next();
            RetainedEntity removed = tombstones.remove(key);
            if (removed != null) tombstoneDigest ^= entryDigest(removed);
            if (removed != null && !removed.clientId().isEmpty()) {
                tombstonesByClient.remove(new ClientIdentity(removed.key().type(), removed.userId(),
                        removed.clientId()));
            }
        }
    }

    boolean containsOrder(long orderId, long userId, String clientOrderId) {
        return contains(EntityType.ORDER, orderId, userId, clientOrderId);
    }

    boolean containsAlgo(long algoOrderId, long userId, String clientAlgoOrderId) {
        return contains(EntityType.ALGO, algoOrderId, userId, clientAlgoOrderId);
    }

    boolean containsTrigger(long triggerOrderId, long userId, String clientTriggerOrderId) {
        return contains(EntityType.TRIGGER, triggerOrderId, userId, clientTriggerOrderId);
    }

    int candidateCount() {
        return candidates.size();
    }

    int tombstoneCount() {
        return tombstones.size();
    }

    CommandFingerprint fundsCommand(UUID commandId) {
        return fundsCommands.get(commandId);
    }

    boolean hasFundsCommandCapacity(UUID commandId) {
        return fundsCommands.containsKey(commandId) || fundsCommands.size() < MAX_FUNDS_COMMANDS;
    }

    void retainFundsCommand(UUID commandId, CommandFingerprint fingerprint) {
        if (commandId == null || fingerprint == null || !hasFundsCommandCapacity(commandId)) {
            throw new IllegalStateException("funds command retention is full");
        }
        CommandFingerprint previous = fundsCommands.putIfAbsent(commandId, fingerprint);
        if (previous != null && !previous.equals(fingerprint)) {
            throw new IllegalStateException("funds command fingerprint conflict");
        }
        if (previous == null) fundsCommandDigest ^= entryDigest(commandId, fingerprint);
    }

    long digest() {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, candidates.size());
        hash = mix(hash, candidateDigest);
        hash = mix(hash, tombstones.size());
        hash = mix(hash, tombstoneDigest);
        hash = mix(hash, fundsCommands.size());
        hash = mix(hash, fundsCommandDigest);
        return hash;
    }

    byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(VERSION);
            write(output, candidates);
            write(output, tombstones);
            output.writeInt(fundsCommands.size());
            for (Map.Entry<UUID, CommandFingerprint> entry : fundsCommands.entrySet()) {
                output.writeLong(entry.getKey().getMostSignificantBits());
                output.writeLong(entry.getKey().getLeastSignificantBits());
                output.write(entry.getValue().bytes());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encode terminal retention", exception);
        }
    }

    TerminalStateRetention copy() {
        return new TerminalStateRetention(new LinkedHashMap<>(candidates), new LinkedHashMap<>(tombstones),
                new LinkedHashMap<>(fundsCommands), candidateDigest, tombstoneDigest, fundsCommandDigest);
    }

    static TerminalStateRetention decode(byte[] encoded) {
        if (encoded == null) throw new IllegalArgumentException("terminal retention payload is required");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != VERSION) throw new IllegalArgumentException("unsupported terminal retention version");
            LinkedHashMap<EntityKey, RetainedEntity> candidates = read(input, Integer.MAX_VALUE);
            LinkedHashMap<EntityKey, RetainedEntity> tombstones = read(input, MAX_TOMBSTONES);
            int fundsCommandCount = input.readInt();
            if (fundsCommandCount < 0 || fundsCommandCount > MAX_FUNDS_COMMANDS) {
                throw new IllegalArgumentException("invalid funds command retention count");
            }
            LinkedHashMap<UUID, CommandFingerprint> fundsCommands = new LinkedHashMap<>();
            for (int index = 0; index < fundsCommandCount; index++) {
                UUID commandId = new UUID(input.readLong(), input.readLong());
                byte[] fingerprint = input.readNBytes(CommandFingerprint.LENGTH);
                if (fingerprint.length != CommandFingerprint.LENGTH
                        || fundsCommands.put(commandId, CommandFingerprint.fromBytes(fingerprint)) != null) {
                    throw new IllegalArgumentException("invalid retained funds command");
                }
            }
            if (input.available() != 0) throw new IllegalArgumentException("trailing terminal retention bytes");
            return new TerminalStateRetention(candidates, tombstones, fundsCommands);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid terminal retention payload", exception);
        }
    }

    private void observeOrder(CoreOrderState order, long exportSequence) {
        if (order == null) return;
        EntityKey key = new EntityKey(EntityType.ORDER, order.orderId());
        if (order.status().terminal()) retain(key, order.userId(), order.clientOrderId(), exportSequence);
        else removeCandidate(key);
    }

    private void observeOrder(OrderRuntime order, long exportSequence) {
        if (order == null) return;
        EntityKey key = new EntityKey(EntityType.ORDER, order.orderId());
        if (order.status().terminal()) retain(key, order.userId(), order.clientOrderId(), exportSequence);
        else removeCandidate(key);
    }

    private void observeAlgo(CoreAlgoOrderState algo, long exportSequence) {
        if (algo == null) return;
        EntityKey key = new EntityKey(EntityType.ALGO, algo.algoOrderId());
        if (algo.terminal()) retain(key, algo.userId(), algo.clientAlgoOrderId(), exportSequence);
        else removeCandidate(key);
    }

    private void observeTrigger(CoreTriggerOrderState trigger, long exportSequence) {
        if (trigger == null) return;
        EntityKey key = new EntityKey(EntityType.TRIGGER, trigger.triggerOrderId());
        if (!trigger.status().open()) retain(key, trigger.userId(), trigger.clientTriggerOrderId(), exportSequence);
        else removeCandidate(key);
    }

    private void observeLiquidation(CoreLiquidationState liquidation, long exportSequence) {
        if (liquidation == null) return;
        EntityKey key = new EntityKey(EntityType.LIQUIDATION, liquidation.liquidationId());
        if (liquidation.terminal()) retain(key, liquidation.userId(), "", exportSequence);
        else removeCandidate(key);
    }

    private void observeLiquidation(LiquidationRuntime liquidation, long exportSequence) {
        if (liquidation == null) return;
        EntityKey key = new EntityKey(EntityType.LIQUIDATION, liquidation.liquidationId());
        if (liquidation.status() == CoreLiquidationState.Status.CANCELED
                || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                && liquidation.deficitUnits() == 0) {
            retain(key, liquidation.userId(), "", exportSequence);
        } else {
            removeCandidate(key);
        }
    }

    private void retain(EntityKey key, long userId, String clientId, long exportSequence) {
        if (tombstones.containsKey(key)) return;
        RetainedEntity retained = new RetainedEntity(key, userId, normalizeClientId(clientId), exportSequence);
        RetainedEntity previous = candidates.put(key, retained);
        if (previous != null) candidateDigest ^= entryDigest(previous);
        candidateDigest ^= entryDigest(retained);
    }

    private void removeCandidate(EntityKey key) {
        RetainedEntity removed = candidates.remove(key);
        if (removed != null) candidateDigest ^= entryDigest(removed);
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
            candidateDigest ^= entryDigest(candidate);
            RetainedEntity previous = tombstones.put(key, candidate);
            if (previous != null) tombstoneDigest ^= entryDigest(previous);
            tombstoneDigest ^= entryDigest(candidate);
            indexTombstone(candidate);
        }
    }

    private boolean contains(EntityType type, long id, long userId, String clientId) {
        if (tombstones.containsKey(new EntityKey(type, id))) return true;
        String normalized = normalizeClientId(clientId);
        if (normalized.isEmpty()) return false;
        return tombstonesByClient.containsKey(new ClientIdentity(type, userId, normalized));
    }

    private void forEachSorted(Iterable<Long> values, java.util.function.LongConsumer consumer) {
        sortedScratch.clear();
        values.forEach(value -> { if (value != null) sortedScratch.add(value); });
        sortedScratch.sort(Comparator.naturalOrder());
        for (long value : sortedScratch) consumer.accept(value);
        sortedScratch.clear();
    }

    private static void write(DataOutputStream output, LinkedHashMap<EntityKey, RetainedEntity> values)
            throws IOException {
        output.writeInt(values.size());
        for (RetainedEntity value : values.values()) {
            output.writeByte(value.key().type().ordinal());
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
            if (typeCode >= EntityType.values().length) throw new IllegalArgumentException("invalid entity type");
            EntityKey key = new EntityKey(EntityType.values()[typeCode], input.readLong());
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
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_CLIENT_ID_BYTES) {
            throw new IllegalArgumentException("terminal client id is too long");
        }
        return normalized;
    }

    private static long entryDigest(RetainedEntity value) {
        long hash = 0xcbf29ce484222325L;
        hash ^= value.key().type().ordinal(); hash *= 0x100000001b3L;
        hash ^= value.key().id(); hash *= 0x100000001b3L;
        hash ^= value.userId(); hash *= 0x100000001b3L;
        hash ^= value.exportSequence(); hash *= 0x100000001b3L;
        for (byte character : value.clientId().getBytes(StandardCharsets.UTF_8)) {
            hash ^= Byte.toUnsignedInt(character); hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long entryDigest(UUID commandId, CommandFingerprint fingerprint) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, commandId.getMostSignificantBits());
        hash = mix(hash, commandId.getLeastSignificantBits());
        for (byte value : fingerprint.bytes()) hash = mix(hash, Byte.toUnsignedInt(value));
        return hash;
    }

    private static long entityDigest(Map<EntityKey, RetainedEntity> values) {
        long digest = 0;
        for (RetainedEntity value : values.values()) digest ^= entryDigest(value);
        return digest;
    }

    private static long fundsCommandDigest(Map<UUID, CommandFingerprint> values) {
        long digest = 0;
        for (Map.Entry<UUID, CommandFingerprint> entry : values.entrySet()) {
            digest ^= entryDigest(entry.getKey(), entry.getValue());
        }
        return digest;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private void indexTombstone(RetainedEntity value) {
        if (!value.clientId().isEmpty()) {
            tombstonesByClient.put(new ClientIdentity(value.key().type(), value.userId(), value.clientId()),
                    value.key());
        }
    }

    private enum EntityType { ORDER, ALGO, TRIGGER, LIQUIDATION }

    private record EntityKey(EntityType type, long id) {
        private EntityKey {
            if (type == null || id <= 0) throw new IllegalArgumentException("invalid terminal entity key");
        }
    }

    private record ClientIdentity(EntityType type, long userId, String clientId) {
    }

    private record RetainedEntity(EntityKey key, long userId, String clientId, long exportSequence) {
        private RetainedEntity {
            if (key == null || userId <= 0 || clientId == null || exportSequence <= 0) {
                throw new IllegalArgumentException("invalid retained terminal entity");
            }
        }
    }
}
