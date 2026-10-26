package com.surprising.aeron.protocol;

import java.util.List;

public final class PlaceOrderBatchCommand {
    private final List<PlaceOrderCommand> orders;
    private final OrderSlots slots;

    public static final int WIRE_VERSION = 1;
    public static final int MAX_ORDERS = 20;
    public static final int MAX_ITEMS = MAX_ORDERS;

    public PlaceOrderBatchCommand(List<PlaceOrderCommand> orders) {
        slots = null;
        if (orders == null || orders.isEmpty() || orders.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("invalid place order batch");
        }
        int count = orders.size();
        PlaceOrderCommand[] array = new PlaceOrderCommand[count];
        int i = 0;
        for (PlaceOrderCommand order : orders) {
            if (order == null) throw new IllegalArgumentException("invalid place order batch");
            array[i++] = order.isCommandSlot() ? order.immutableValue() : order;
        }
        this.orders = List.of(array);
    }

    private PlaceOrderBatchCommand() { slots = new OrderSlots(); orders = slots; }
    public static PlaceOrderBatchCommand commandSlot() { return new PlaceOrderBatchCommand(); }
    public List<PlaceOrderCommand> orders() { return orders; }
    public int size() { return orders.size(); }
    public PlaceOrderCommand orderAt(int index) { return orders.get(index); }
    void activeCount(int count) {
        if (slots == null) throw new IllegalStateException("batch is not a decoder slot");
        slots.count = count;
    }
    PlaceOrderCommand orderSlot(int index) {
        if (slots == null || index < 0 || index >= slots.count) throw new IllegalStateException("invalid decoder item");
        if (slots.values[index] == null) slots.values[index] = PlaceOrderCommand.commandSlot();
        return slots.values[index];
    }
    public void clearCommandSlot() {
        if (slots == null) throw new IllegalStateException("cannot clear an immutable batch");
        for (int index = 0; index < slots.count; index++) if (slots.values[index] != null) slots.values[index].clearCommandSlot();
        slots.count = 0;
    }
    private static final class OrderSlots extends java.util.AbstractList<PlaceOrderCommand> implements java.util.RandomAccess {
        final PlaceOrderCommand[] values = new PlaceOrderCommand[MAX_ORDERS];
        int count;
        @Override public PlaceOrderCommand get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
            return values[index];
        }
        @Override public int size() { return count; }
    }
    @Override public boolean equals(Object other) { return other instanceof PlaceOrderBatchCommand value && orders.equals(value.orders); }
    @Override public int hashCode() { return orders.hashCode(); }
    @Override public String toString() { return "PlaceOrderBatchCommand[orders=" + orders + "]"; }

    public List<PlaceOrderCommand> items() {
        return orders;
    }
}
