package com.surprising.aeron.protocol;

import java.util.List;

public final class AmendOrderBatchCommand {
    private final List<AmendOrderCommand> orders;
    private final OrderSlots slots;

    public static final int WIRE_VERSION = 1;
    public static final int MAX_ORDERS = 20;
    public static final int MAX_ITEMS = MAX_ORDERS;

    public AmendOrderBatchCommand(List<AmendOrderCommand> orders) {
        slots = null;
        if (orders == null || orders.isEmpty() || orders.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("invalid amend order batch");
        }
        int count = orders.size();
        AmendOrderCommand[] array = new AmendOrderCommand[count];
        int i = 0;
        for (AmendOrderCommand order : orders) {
            if (order == null) throw new IllegalArgumentException("invalid amend order batch");
            array[i++] = order.isCommandSlot() ? order.immutableValue() : order;
        }
        this.orders = List.of(array);
    }

    private AmendOrderBatchCommand() { slots = new OrderSlots(); orders = slots; }
    public static AmendOrderBatchCommand commandSlot() { return new AmendOrderBatchCommand(); }
    public List<AmendOrderCommand> orders() { return orders; }
    public int size() { return orders.size(); }
    public AmendOrderCommand orderAt(int index) { return orders.get(index); }
    void activeCount(int count) {
        if (slots == null) throw new IllegalStateException("batch is not a decoder slot");
        slots.count = count;
    }
    AmendOrderCommand orderSlot(int index) {
        if (slots == null || index < 0 || index >= slots.count)
            throw new IllegalStateException("invalid amend decoder item");
        if (slots.values[index] == null) slots.values[index] = AmendOrderCommand.commandSlot();
        return slots.values[index];
    }
    public void clearCommandSlot() {
        if (slots == null) throw new IllegalStateException("cannot clear an immutable batch");
        for (int index = 0; index < slots.count; index++)
            if (slots.values[index] != null) slots.values[index].clearCommandSlot();
        slots.count = 0;
    }
    private static final class OrderSlots extends java.util.AbstractList<AmendOrderCommand>
            implements java.util.RandomAccess {
        final AmendOrderCommand[] values = new AmendOrderCommand[MAX_ORDERS];
        int count;
        @Override public AmendOrderCommand get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
            return values[index];
        }
        @Override public int size() { return count; }
    }
    @Override public boolean equals(Object other) {
        return other instanceof AmendOrderBatchCommand value && orders.equals(value.orders);
    }
    @Override public int hashCode() { return orders.hashCode(); }
    @Override public String toString() { return "AmendOrderBatchCommand[orders=" + orders + "]"; }

    public List<AmendOrderCommand> items() {
        return orders;
    }
}
