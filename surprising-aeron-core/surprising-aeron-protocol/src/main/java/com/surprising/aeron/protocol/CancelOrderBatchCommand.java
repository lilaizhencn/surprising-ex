package com.surprising.aeron.protocol;

import java.util.List;

public final class CancelOrderBatchCommand {
    private final List<CancelOrderCommand> orders;
    private final OrderSlots slots;

    public static final int WIRE_VERSION = 1;
    public static final int MAX_ORDERS = 50;
    public static final int MAX_ITEMS = MAX_ORDERS;

    public CancelOrderBatchCommand(List<CancelOrderCommand> orders) {
        slots = null;
        if (orders == null || orders.isEmpty() || orders.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("invalid cancel order batch");
        }
        int count = orders.size();
        CancelOrderCommand[] array = new CancelOrderCommand[count];
        int i = 0;
        for (CancelOrderCommand order : orders) {
            if (order == null) throw new IllegalArgumentException("invalid cancel order batch");
            array[i++] = order.isCommandSlot() ? order.immutableValue() : order;
        }
        this.orders = List.of(array);
    }

    private CancelOrderBatchCommand() { slots = new OrderSlots(); orders = slots; }
    public static CancelOrderBatchCommand commandSlot() { return new CancelOrderBatchCommand(); }
    public List<CancelOrderCommand> orders() { return orders; }
    public int size() { return orders.size(); }
    public CancelOrderCommand orderAt(int index) { return orders.get(index); }
    void activeCount(int count) {
        if (slots == null) throw new IllegalStateException("batch is not a decoder slot");
        slots.count = count;
    }
    CancelOrderCommand orderSlot(int index) {
        if (slots == null || index < 0 || index >= slots.count)
            throw new IllegalStateException("invalid cancel decoder item");
        if (slots.values[index] == null) slots.values[index] = CancelOrderCommand.commandSlot();
        return slots.values[index];
    }
    public void clearCommandSlot() {
        if (slots == null) throw new IllegalStateException("cannot clear an immutable batch");
        for (int index = 0; index < slots.count; index++)
            if (slots.values[index] != null) slots.values[index].clearCommandSlot();
        slots.count = 0;
    }
    private static final class OrderSlots extends java.util.AbstractList<CancelOrderCommand>
            implements java.util.RandomAccess {
        final CancelOrderCommand[] values = new CancelOrderCommand[MAX_ORDERS];
        int count;
        @Override public CancelOrderCommand get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
            return values[index];
        }
        @Override public int size() { return count; }
    }
    @Override public boolean equals(Object other) {
        return other instanceof CancelOrderBatchCommand value && orders.equals(value.orders);
    }
    @Override public int hashCode() { return orders.hashCode(); }
    @Override public String toString() { return "CancelOrderBatchCommand[orders=" + orders + "]"; }

    public List<CancelOrderCommand> items() {
        return orders;
    }
}
