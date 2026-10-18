package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.state.TradingRuntimeState;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import java.lang.reflect.Field;

/** Rejects Owner account reads when independent Lanes own the tables. FUSED Owner owns all Lanes. */
final class OwnerAccountReadGuard implements AutoCloseable {
    private final Object[] lanes;
    private final String[] names = {"orders", "reservations", "users", "positions", "balances"};
    private final Object[][] originals;
    private final Thread owner = Thread.currentThread();
    private final TradingRuntimeState runtime;
    private final String[] changeNames = {"changedUsers", "changedReservations"};
    private final LongHashSet[] originalChanges = new LongHashSet[changeNames.length];

    OwnerAccountReadGuard(TradingRuntimeState runtime) throws ReflectiveOperationException {
        this.runtime = runtime;
        Field field = runtime.getClass().getDeclaredField("accountLanes");
        field.setAccessible(true);
        lanes = (Object[]) field.get(runtime);
        originals = new Object[lanes.length][names.length];
        for (int lane = 0; lane < lanes.length; lane++) {
            for (int index = 0; index < names.length; index++) {
                Field table = table(lane, index);
                originals[lane][index] = table.get(lanes[lane]);
                table.set(lanes[lane], guard(originals[lane][index], names[index]));
            }
        }
        for (int index = 0; index < changeNames.length; index++) {
            Field changes = changeField(index);
            var source = (LongHashSet) changes.get(runtime);
            originalChanges[index] = source;
            String name = changeNames[index];
            var guard = new LongHashSet() {
                @Override public boolean add(long key) {
                    if (Thread.currentThread() != owner) throw new AssertionError("Lane wrote Owner " + name);
                    return super.add(key);
                }
            };
            source.forEach(guard::add);
            changes.set(runtime, guard);
        }
    }

    private Field changeField(int index) throws ReflectiveOperationException {
        Field field = runtime.getClass().getDeclaredField(changeNames[index]);
        field.setAccessible(true);
        return field;
    }

    private Field table(int lane, int index) throws ReflectiveOperationException {
        Field table = lanes[lane].getClass().getDeclaredField(names[index]);
        table.setAccessible(true);
        return table;
    }

    private boolean forbiddenOwnerRead() {
        return Thread.currentThread() == owner
                && !runtime.isFusedExecution();
    }

    @SuppressWarnings("unchecked")
    private Object guard(Object original, String name) {
        if (original instanceof Long2ObjectHashMap<?> source) {
            var guard = new Long2ObjectHashMap<Object>() {
                @Override public Object get(long key) {
                    if (forbiddenOwnerRead()) throw new AssertionError("Owner read Lane " + name);
                    return super.get(key);
                }
            };
            guard.putAll((Long2ObjectHashMap<Object>) source);
            return guard;
        }
        var guard = new LongObjectHashMap<Object>() {
            @Override public Object get(long key) {
                if (forbiddenOwnerRead()) throw new AssertionError("Owner read Lane " + name);
                return super.get(key);
            }
        };
        guard.putAll((LongObjectHashMap<Object>) original);
        return guard;
    }

    @SuppressWarnings("unchecked")
    @Override public void close() throws ReflectiveOperationException {
        for (int lane = 0; lane < lanes.length; lane++) {
            for (int index = 0; index < names.length; index++) {
                Field table = table(lane, index);
                Object original = originals[lane][index];
                Object current = table.get(lanes[lane]);
                if (original instanceof Long2ObjectHashMap<?>) {
                    var target = (Long2ObjectHashMap<Object>) original;
                    target.clear(); target.putAll((Long2ObjectHashMap<Object>) current);
                } else {
                    var target = (LongObjectHashMap<Object>) original;
                    target.clear(); target.putAll((LongObjectHashMap<Object>) current);
                }
                table.set(lanes[lane], original);
            }
        }
        for (int index = 0; index < changeNames.length; index++) {
            Field changes = changeField(index);
            var source = (LongHashSet) changes.get(runtime);
            originalChanges[index].clear();
            originalChanges[index].addAll(source);
            changes.set(runtime, originalChanges[index]);
        }
    }
}
