package com.surprising.aeron.service.command.support;

/** Read-only primitive long view used at internal collection boundaries. */
public interface PrimitiveLongView {
    int primitiveSize();
    long primitiveValueAt(int index);
}
