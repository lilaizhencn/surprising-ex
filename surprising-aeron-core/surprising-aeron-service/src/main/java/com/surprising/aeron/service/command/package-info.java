/**
 * Command-side protocol decoding, value types and business command handlers.
 *
 * <p>Business handlers are grouped below this package by responsibility (order, position,
 * leverage, risk, liquidation, ADL, funding and settlement). This package does not own matcher
 * threads, account lane state, or ordered owner commit.</p>
 */
package com.surprising.aeron.service.command;
