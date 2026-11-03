# Aeron Protocol

Shared wire contracts for six product lines: envelopes, commands, query responses, and realtime frames. Product identity, instrument identity, units, precision, enum values, and result codes are cross-service contracts.

| Entry point | Responsibility |
| --- | --- |
| `CoreMessageHeader`, `CoreMessageCodec` | Command identity, source progress, product, and frame bounds |
| `TradingCommandCodec`, `TradingOrderBatchCodec` | Ordinary, control, and batch payloads |
| `CoreProtocol` | Terminal responses and current command schema |
| Query codecs | Deterministic request/result layouts |
| `RealtimeFrameCodec` | Realtime frames, batch boundaries, and read requests |

## Buffer ownership

`CoreMessage.payloadLength()` is the valid length; `payloadUnsafe()` may include unused capacity. Copy APIs produce retainable data. Borrowed inputs remain valid only within their explicit leases. Decoded slots cannot be reused before command completion and consumer release. Owner returns the input lease after Matcher/account work and response encoding.

`CoreResponse.data()` returns a defensive copy. Borrowed response slices require offset and length together. `RealtimeFrameCodec.decode(ByteBuffer)` reads position-to-limit without changing the caller's cursor and validates length, identity, enums, truncation, and trailing data; decoded output does not borrow the transport buffer.

## Verification

```bash
mvn -pl surprising-aeron-core/surprising-aeron-protocol -am test
```

Use source constants for current formats and versions. Update every producer, consumer, and affected frontend when changing fields, enum values, or units. Protocol tests do not replace funds or recovery verification.

## Architecture reference

See the [project architecture and class-level trading flows](../../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
