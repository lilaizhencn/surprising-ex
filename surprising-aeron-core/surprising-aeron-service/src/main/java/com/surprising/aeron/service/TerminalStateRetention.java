package com.surprising.aeron.service;

import com.surprising.aeron.service.state.CoreAlgoOrderState;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreTriggerOrderState;
import com.surprising.aeron.service.state.TerminalPruneBatch;
import com.surprising.aeron.service.state.TradingCoreState;
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

final class TerminalStateRetention {

    static final int MAX_TOMBSTONES = 65_536;
    static final int MAX_PRUNE_PER_ACK = 4_096;
    private static final int VERSION = 1;
    private static final int MAX_CLIENT_ID_BYTES = 256;
    private final LinkedHashMap<EntityKey, RetainedEntity> candidates;
    private final LinkedHashMap<EntityKey, RetainedEntity> tombstones;
    private final Map<ClientIdentity, EntityKey> tombstonesByClient;

    TerminalStateRetention() {
        this(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private TerminalStateRetention(LinkedHashMap<EntityKey, RetainedEntity> candidates,
                                   LinkedHashMap<EntityKey, RetainedEntity> tombstones) {
        this.candidates = candidates;
        this.tombstones = tombstones;
        this.tombstonesByClient = new HashMap<>();
        tombstones.values().forEach(this::indexTombstone);
    }

    void observe(TradingCoreState before, TradingCoreState after, long exportSequence,
                 Iterable<Long> orderIds, Iterable<Long> liquidationIds, Iterable<Long> triggerOrderIds) {
        if (before == null || after == null || exportSequence <= 0) {
            throw new IllegalArgumentException("invalid terminal retention observation");
        }
        sorted(orderIds).forEach(id -> observeOrder(after.orders().get(id), exportSequence));
        sorted(after.changedAlgoOrderIds()).forEach(id -> observeAlgo(after.algoOrders().get(id), exportSequence));
        sorted(triggerOrderIds).forEach(id ->
                observeTrigger(after.triggerOrders().get(id), exportSequence));
        sorted(liquidationIds).forEach(id ->
                observeLiquidation(after.riskState().liquidations().get(id), exportSequence));
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

    void complete(TerminalPruneBatch batch, long acknowledgedSequence) {
        if (batch == null || acknowledgedSequence < 0) {
            throw new IllegalArgumentException("invalid completed terminal prune");
        }
        complete(EntityType.ORDER, batch.orderIds(), acknowledgedSequence);
        complete(EntityType.ALGO, batch.algoOrderIds(), acknowledgedSequence);
        complete(EntityType.TRIGGER, batch.triggerOrderIds(), acknowledgedSequence);
        complete(EntityType.LIQUIDATION, batch.liquidationIds(), acknowledgedSequence);
        while (tombstones.size() > MAX_TOMBSTONES) {
            EntityKey key = tombstones.keySet().iterator().next();
            RetainedEntity removed = tombstones.remove(key);
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

    long digest() {
        long hash = 0xcbf29ce484222325L;
        for (RetainedEntity value : candidates.values()) hash = mix(hash, value);
        hash ^= 0x9e3779b97f4a7c15L;
        for (RetainedEntity value : tombstones.values()) hash = mix(hash, value);
        return hash;
    }

    byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(VERSION);
            write(output, candidates);
            write(output, tombstones);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encode terminal retention", exception);
        }
    }

    static TerminalStateRetention decode(byte[] encoded) {
        if (encoded == null) throw new IllegalArgumentException("terminal retention payload is required");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != VERSION) throw new IllegalArgumentException("unsupported terminal retention version");
            LinkedHashMap<EntityKey, RetainedEntity> candidates = read(input, Integer.MAX_VALUE);
            LinkedHashMap<EntityKey, RetainedEntity> tombstones = read(input, MAX_TOMBSTONES);
            if (input.available() != 0) throw new IllegalArgumentException("trailing terminal retention bytes");
            return new TerminalStateRetention(candidates, tombstones);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid terminal retention payload", exception);
        }
    }

    private void observeOrder(CoreOrderState order, long exportSequence) {
        if (order == null) return;
        EntityKey key = new EntityKey(EntityType.ORDER, order.orderId());
        if (order.status().terminal()) retain(key, order.userId(), order.clientOrderId(), exportSequence);
        else candidates.remove(key);
    }

    private void observeAlgo(CoreAlgoOrderState algo, long exportSequence) {
        if (algo == null) return;
        EntityKey key = new EntityKey(EntityType.ALGO, algo.algoOrderId());
        if (algo.terminal()) retain(key, algo.userId(), algo.clientAlgoOrderId(), exportSequence);
        else candidates.remove(key);
    }

    private void observeTrigger(CoreTriggerOrderState trigger, long exportSequence) {
        if (trigger == null) return;
        EntityKey key = new EntityKey(EntityType.TRIGGER, trigger.triggerOrderId());
        if (!trigger.status().open()) retain(key, trigger.userId(), trigger.clientTriggerOrderId(), exportSequence);
        else candidates.remove(key);
    }

    private void observeLiquidation(CoreLiquidationState liquidation, long exportSequence) {
        if (liquidation == null) return;
        EntityKey key = new EntityKey(EntityType.LIQUIDATION, liquidation.liquidationId());
        if (liquidation.terminal()) retain(key, liquidation.userId(), "", exportSequence);
        else candidates.remove(key);
    }

    private void retain(EntityKey key, long userId, String clientId, long exportSequence) {
        candidates.put(key, new RetainedEntity(key, userId, normalizeClientId(clientId), exportSequence));
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

    private void complete(EntityType type, List<Long> ids, long acknowledgedSequence) {
        for (long id : ids) {
            EntityKey key = new EntityKey(type, id);
            RetainedEntity candidate = candidates.remove(key);
            if (candidate == null || candidate.exportSequence() > acknowledgedSequence) {
                throw new IllegalStateException("terminal entity was not eligible for pruning: " + key);
            }
            tombstones.put(key, candidate);
            indexTombstone(candidate);
        }
    }

    private boolean contains(EntityType type, long id, long userId, String clientId) {
        if (tombstones.containsKey(new EntityKey(type, id))) return true;
        String normalized = normalizeClientId(clientId);
        if (normalized.isEmpty()) return false;
        return tombstonesByClient.containsKey(new ClientIdentity(type, userId, normalized));
    }

    private static List<Long> sorted(Iterable<Long> values) {
        ArrayList<Long> result = new ArrayList<>();
        values.forEach(value -> { if (value != null) result.add(value); });
        result.sort(Comparator.naturalOrder());
        return result;
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

    private static long mix(long hash, RetainedEntity value) {
        hash ^= value.key().type().ordinal(); hash *= 0x100000001b3L;
        hash ^= value.key().id(); hash *= 0x100000001b3L;
        hash ^= value.userId(); hash *= 0x100000001b3L;
        hash ^= value.exportSequence(); hash *= 0x100000001b3L;
        for (byte character : value.clientId().getBytes(StandardCharsets.UTF_8)) {
            hash ^= Byte.toUnsignedInt(character); hash *= 0x100000001b3L;
        }
        return hash;
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
