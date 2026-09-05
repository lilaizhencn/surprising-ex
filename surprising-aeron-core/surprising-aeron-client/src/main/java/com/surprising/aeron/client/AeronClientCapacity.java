package com.surprising.aeron.client;

public record AeronClientCapacity(
        int commandSessions,
        int reservedControlSessions,
        int commandMailboxCapacity,
        int queryMailboxCapacity,
        int maxCommandInFlightPerSession,
        int maxReservedInFlight,
        int egressFragmentLimit) {

    private static final AeronClientCapacity DEFAULTS =
            new AeronClientCapacity(4, 1, 256, 64, 64, 32, 32);

    public AeronClientCapacity {
        requirePositive(commandSessions, "commandSessions");
        requirePositive(reservedControlSessions, "reservedControlSessions");
        if (reservedControlSessions != 1) {
            throw new IllegalArgumentException("reservedControlSessions must be exactly one");
        }
        requirePositive(commandMailboxCapacity, "commandMailboxCapacity");
        requirePositive(queryMailboxCapacity, "queryMailboxCapacity");
        requirePositive(maxCommandInFlightPerSession, "maxCommandInFlightPerSession");
        requirePositive(maxReservedInFlight, "maxReservedInFlight");
        requirePositive(egressFragmentLimit, "egressFragmentLimit");
    }

    public static AeronClientCapacity defaults() {
        return DEFAULTS;
    }

    public AeronClientCapacity withCommandSessions(int sessions) {
        return new AeronClientCapacity(sessions, reservedControlSessions, commandMailboxCapacity,
                queryMailboxCapacity, maxCommandInFlightPerSession, maxReservedInFlight, egressFragmentLimit);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
