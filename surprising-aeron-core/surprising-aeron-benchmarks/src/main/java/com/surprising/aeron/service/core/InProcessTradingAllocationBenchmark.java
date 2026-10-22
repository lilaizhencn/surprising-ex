package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.TradingOwnerLoop;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.aeron.support.ingress.CoreMessageFlyweightDecoder;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Event;
import jdk.jfr.Name;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;

/**
 * Real decoder/Owner/Matcher/Lane/Ledger/response path. The two users route to different Account
 * Lanes, exercising settlement-slot reuse from single-Lane admission to cross-Lane fills.
 * Unique production-range IDs also exercise active-order and reservation membership deletion
 * while the rolling window retains live entries; this is not a repeated-key map benchmark.
 * Admissions borrow the existing immutable Treasury status. FIXED keeps normal pipeline
 * admission; PROPORTIONAL exercises ordered Ledger OI input and sequential batch prefixes.
 * Sequential batches exercise frozen Matcher inputs and one captured context per symbol.
 * symbols=2 interleaves the two books inside a batch; teardown checks each position.
 * Single requests include the production input-wrapper return queue and the leased response writer.
 * Terminal queries also check user revisions after resident user/receipt reuse, outside measurement.
 * ASCII/UTF8 client prefixes exercise the same Lane identity lifecycle with unique names.
 * Aeron transport and Archive are excluded.
 */
@State(Scope.Thread) @BenchmarkMode(Mode.Throughput) @OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 10) @Measurement(iterations = 3, time = 5)
@Fork(value = 1, jvmArgsAppend = {"--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g", "-XX:+UseG1GC",
        "-Dsurprising.aeron.matching-engines=1", "-Dsurprising.aeron.account-lanes=2",
        "-Dsurprising.aeron.owner-command-window=256", "-Dsurprising.aeron.matcher-window-size=256",
        "-Dsurprising.aeron.owner-wait-strategy=BUSY_SPIN", "-Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN",
        "-Dsurprising.aeron.matcher-pipeline-wait-strategy=BUSY_SPIN", "-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN"})
public class InProcessTradingAllocationBenchmark {
    private static final int WINDOW = 256, OPERATIONS = 4096;
    private static final long TIME = 1_700_000_000_000L, INITIAL_BALANCE = 1_000_000_000_000L;
    @Param({"1", "20"}) public int batchSize;
    @Param({"OPEN_ONLY", "REVERSE"}) public String streamMode;
    @Param({"ASCII", "UTF8"}) public String clientIdMode;
    private final CoreMessageFlyweightDecoder decoder = new CoreMessageFlyweightDecoder();
    private final CommandFingerprint.Calculator fingerprints = new CommandFingerprint.Calculator();
    private final UnsafeBuffer[][] frames = new UnsafeBuffer[2][WINDOW];
    private final int[][][] orderOffsets = new int[2][WINDOW][];
    private final int[][][] clientOffsets = new int[2][WINDOW][];
    private final Sink session = new Sink();
    private final ClusterServiceEgress egress = new ClusterServiceEgress((cluster, target) -> session);
    private long sessionId;
    private TradingOwnerLoop owner;
    private long sequence, orderId = 1_000_000, submitted;
    private Interval interval;

    @Param({"FIXED", "PROPORTIONAL"}) public String oiLimitMode;
    @Param({"1"}) public int symbols;
    @Param({"2"}) public int accountLanes;
    private String previousAccountLanes;

    @Setup public void setup() {
        if (!streamMode.equals("OPEN_ONLY") && !streamMode.equals("REVERSE"))
            throw new IllegalArgumentException("streamMode must be OPEN_ONLY or REVERSE");
        if (symbols != 1 && symbols != 2) throw new IllegalArgumentException("symbols must be 1 or 2");
        if (!clientIdMode.equals("ASCII") && !clientIdMode.equals("UTF8"))
            throw new IllegalArgumentException("clientIdMode must be ASCII or UTF8");
        if (accountLanes != 2 && accountLanes != 4)
            throw new IllegalArgumentException("accountLanes must be 2 or 4");
        previousAccountLanes = System.setProperty("surprising.aeron.account-lanes", Integer.toString(accountLanes));
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "memberId" -> 0;
                    case "logPosition" -> 1L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "time" -> TIME;
                    case "scheduleTimer" -> true;
                    default -> null;
                });
        egress.start(cluster);
        var actual = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> 1L;
                    case "isClosing" -> false;
                    case "close" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        sessionId = egress.transportSession(actual).id();
        owner = new TradingOwnerLoop(ProductLine.LINEAR_PERPETUAL, egress);
        owner.start(cluster, null);
        if (!oiLimitMode.equals("FIXED") && !oiLimitMode.equals("PROPORTIONAL"))
            throw new IllegalArgumentException("invalid OI input mode");
        for (int symbol = 0; symbol < symbols; symbol++) {
            String instrumentId = Integer.toString(symbol + 1);
            var instrument = oiLimitMode.equals("FIXED")
                    ? new RegisterInstrumentCommand(instrumentId, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                            1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0)
                    : new RegisterInstrumentCommand(instrumentId, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                            1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0, 10_000_000,
                            Long.MAX_VALUE, 1_000_000, Long.MAX_VALUE / 2,
                            java.util.List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE, 10_000_000, 100_000, 50_000)));
            control(CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(instrument));
            control(CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(instrumentId, 100, 1, TIME)));
        }
        for (long user : new long[]{7, 8}) control(CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", INITIAL_BALANCE)));
        for (int direction = 0; direction < 2; direction++) for (int index = 0; index < WINDOW; index++) {
            boolean sell = ((index & 1) ^ (streamMode.equals("REVERSE") ? direction : 0)) == 0;
            var orders = new ArrayList<PlaceOrderCommand>(batchSize);
            for (int item = 0; item < batchSize; item++) orders.add(new PlaceOrderCommand(item + 1,
                    Integer.toString((((index >>> 1) * batchSize) + item) % symbols + 1),
                    sell ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 1, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                    false, (clientIdMode.equals("UTF8") ? "客户😀-" : "") + "0000000000000000"));
            var type = batchSize == 1 ? CoreMessageType.PLACE_ORDER : CoreMessageType.PLACE_ORDER_BATCH;
            byte[] payload = batchSize == 1 ? TradingCommandCodec.encodePlaceOrder(orders.getFirst())
                    : TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders));
            var frame = new UnsafeBuffer(CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(type,
                    new UUID(945, 1), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 945, 1,
                    (index & 1) == 0 ? 7 : 8, TIME, 1), payload)));
            frames[direction][index] = frame;
            int[] offsets = orderOffsets[direction][index] = new int[batchSize];
            int[] clients = clientOffsets[direction][index] = new int[batchSize];
            if (batchSize == 1) {
                offsets[0] = CoreProtocol.HEADER_LENGTH + Integer.BYTES;
                clients[0] = frame.capacity() - 16;
            }
            else {
                int cursor = CoreProtocol.HEADER_LENGTH + Integer.BYTES * 2;
                for (int item = 0; item < batchSize; item++) {
                    offsets[item] = cursor + Integer.BYTES * 3;
                    clients[item] = cursor + Integer.BYTES * 2
                            + frame.getInt(cursor + Integer.BYTES, ByteOrder.LITTLE_ENDIAN) - 16;
                    cursor += Integer.BYTES * 2 + frame.getInt(cursor + Integer.BYTES, ByteOrder.LITTLE_ENDIAN);
                }
                if (cursor != frame.capacity()) throw new IllegalStateException("invalid batch template offsets");
            }
        }
        session.batchSize = batchSize;
        session.trading = true;
    }

    @Setup(Level.Iteration) public void beginInterval(IterationParams params) {
        interval = new Interval(); interval.phase = params.getType().name(); interval.batchSize = batchSize;
        interval.streamMode = streamMode;
        interval.oiLimitMode = oiLimitMode;
        interval.symbols = symbols;
        interval.startedMessages = session.tradingMessages;
        interval.startedAtEpochMillis = System.currentTimeMillis();
        interval.begin();
    }

    /** JMH normalizes per Core message; divide gc.alloc.rate.norm by batchSize for B/business item. */
    @Benchmark @OperationsPerInvocation(OPERATIONS)
    public long continuousMatchStream() {
        long start = session.received, goal = start + OPERATIONS;
        int sent = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (session.received != goal) {
            while (sent < OPERATIONS && sent - (session.received - start) < WINDOW) {
                int index = (int) (submitted & (WINDOW - 1));
                int direction = (int) ((submitted / WINDOW) & 1);
                UnsafeBuffer frame = frames[direction][index];
                long id = ++sequence;
                // Fixed protocol header fields; payload layout offsets are derived from the real encoder.
                frame.putLong(24, id, ByteOrder.LITTLE_ENDIAN);
                frame.putLong(40, id, ByteOrder.LITTLE_ENDIAN);
                frame.putLong(64, id, ByteOrder.LITTLE_ENDIAN);
                for (int item = 0; item < batchSize; item++) {
                    long nextOrder = ++orderId;
                    frame.putLong(orderOffsets[direction][index][item], nextOrder, ByteOrder.LITTLE_ENDIAN);
                    int client = clientOffsets[direction][index][item];
                    for (int digit = 15; digit >= 0; digit--) {
                        frame.putByte(client + digit, (byte) ('0' + nextOrder % 10));
                        nextOrder /= 10;
                    }
                    if (nextOrder != 0) throw new IllegalStateException("benchmark identity exceeds its frame");
                }
                enqueue(frame, id, true);
                submitted++; sent++;
            }
            owner.drainResponses(); owner.checkFailure();
            if (System.nanoTime() >= deadline) throw new IllegalStateException("trading window failed to drain");
            Thread.onSpinWait();
        }
        return session.tradingMessages;
    }

    @TearDown(Level.Iteration) public void finishInterval() {
        interval.end();
        interval.finishedAtEpochMillis = System.currentTimeMillis();
        interval.terminalMessages = session.tradingMessages - interval.startedMessages;
        interval.terminalBusinessOperations = interval.terminalMessages * batchSize;
        interval.commit();
        // One marker per iteration, outside the trading loop, joins GCProfiler runs to host samples.
        System.out.println("pipelineInterval phase=" + interval.phase + " batchSize=" + batchSize
                + " startEpochMillis=" + interval.startedAtEpochMillis + " endEpochMillis=" + interval.finishedAtEpochMillis
                + " terminalMessages=" + interval.terminalMessages + " terminalBusinessOperations=" + interval.terminalBusinessOperations
                + " streamMode=" + streamMode + " oiLimitMode=" + oiLimitMode + " symbols=" + symbols
                + " accountLanes=" + accountLanes);
    }

    @TearDown public void close() {
        try {
            if (submitted != session.tradingMessages) throw new IllegalStateException("unfinished trading messages");
            session.trading = false;
            for (long user : new long[]{7, 8}) {
                var state = userState(user);
                // Admission and fill each advance once per order. This fixture's maker (user7)
                // also advances on terminal release (rounding remainder on open, unused budget on close).
                long expectedRevision = Math.addExact(1, Math.multiplyExact(submitted, batchSize));
                if (user == 7) expectedRevision = Math.addExact(expectedRevision, submitted * batchSize / 2);
                else if (streamMode.equals("REVERSE"))
                    expectedRevision = Math.addExact(expectedRevision, submitted * batchSize / 4);
                if (state.revision() != expectedRevision
                        || state.positionMode() != CorePositionMode.ONE_WAY)
                    throw new IllegalStateException("terminal user metadata differs user=" + user
                            + " revision=" + state.revision() + " expectedRevision=" + expectedRevision);
                long units = 0, quantity = 0;
                for (var balance : state.balances()) units = Math.addExact(units, Math.addExact(balance.availableUnits(), balance.lockedUnits()));
                for (var position : state.positions()) {
                    // Balance.lockedUnits already includes position margin; do not count it twice.
                    quantity = Math.addExact(quantity, position.signedQuantitySteps());
                }
                long expectedQuantity = streamMode.equals("REVERSE") ? 0 : submitted * batchSize / 2 * (user == 7 ? -1 : 1);
                if (!streamMode.equals("REVERSE") && state.positions().size() != symbols)
                    throw new IllegalStateException("missing symbol position");
                for (var position : state.positions())
                    if (position.signedQuantitySteps() != expectedQuantity / symbols)
                        throw new IllegalStateException("per-symbol terminal quantity differs");
                if (units != INITIAL_BALANCE || quantity != expectedQuantity || !state.reservations().isEmpty())
                    throw new IllegalStateException("terminal account/funds differ user=" + user + " units=" + units
                            + " quantity=" + quantity + " expectedQuantity=" + expectedQuantity + " reservations=" + state.reservations());
            }
            System.out.println("pipelineValidation=PASS messages=" + submitted + " businessOperations=" + submitted * batchSize
                    + " unfinished=0 fundsDiff=0 batchSize=" + batchSize);
        } finally {
            try { if (owner != null) owner.terminate(); egress.clear(); }
            finally {
                if (previousAccountLanes == null) System.clearProperty("surprising.aeron.account-lanes");
                else System.setProperty("surprising.aeron.account-lanes", previousAccountLanes);
            }
        }
    }

    private CoreUserStateView userState(long user) {
        long id = ++sequence, expected = session.received + 1;
        session.readUser = true;
        var frame = new UnsafeBuffer(CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.query(
                CoreMessageType.USER_STATE_QUERY, new UUID(946, id), ProductLine.LINEAR_PERPETUAL,
                CommandSource.OPERATIONS, 946, id, user, TIME, id), CoreMessage.EMPTY_PAYLOAD)));
        enqueue(frame, id, false); await(expected);
        session.readUser = false;
        return CoreStateQueryCodec.decodeUserState(session.userData);
    }

    private void control(CoreMessageType type, long user, byte[] payload) {
        long id = ++sequence, expected = session.received + 1;
        var frame = new UnsafeBuffer(CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(type,
                new UUID(947, id), ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS, 947, id, user, TIME, id), payload)));
        enqueue(frame, id, true); await(expected);
    }

    private void enqueue(UnsafeBuffer frame, long position, boolean command) {
        var message = decoder.decodeBorrowed(frame, 0, frame.capacity());
        if (session.trading && (submitted & (WINDOW - 1)) == 0) {
            int offset = batchSize == 1 ? 4 : 20;
            long actual = 0;
            byte[] payload = message.payloadUnsafe();
            for (int i = 0; i < 8; i++) actual |= (payload[offset + i] & 255L) << (i * 8);
            if (actual != orderId - batchSize + 1)
                throw new IllegalStateException("source payload ID differs from its encoded template: " + actual + " != " + (orderId - batchSize + 1));
        }
        owner.enqueue(message, sessionId, TIME, position, command ? fingerprints.ofBorrowed(message) : null);
    }

    private void await(long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (session.received < expected) {
            owner.drainResponses(); owner.checkFailure();
            if (System.nanoTime() >= deadline) throw new IllegalStateException("control/query failed to drain");
            Thread.onSpinWait();
        }
    }

    @Name("surprising.InProcessAllocationInterval")
    static final class Interval extends Event {
        public String phase;
        public String streamMode;
        public String oiLimitMode;
        public int batchSize;
        public int symbols;
        public long startedMessages, terminalMessages, terminalBusinessOperations;
        public long startedAtEpochMillis, finishedAtEpochMillis;
    }

    private static final class Sink implements ClusterServiceEgress.SessionEgress {
        private long received, tradingMessages;
        private int batchSize;
        private boolean trading, readUser;
        private byte[] userData;
        public void close() { }
        public long offer(long term, long timestamp, DirectBuffer buffer, int offset, int length) {
            int body = offset + Short.toUnsignedInt(buffer.getShort(offset + 12, ByteOrder.LITTLE_ENDIAN));
            int status = buffer.getInt(body + 4, ByteOrder.LITTLE_ENDIAN);
            if (status != (readUser ? ResponseStatus.OK : ResponseStatus.APPLIED).wireCode())
                throw new IllegalStateException("non-applied terminal response status=" + status
                        + " code=" + CoreResultCode.fromWireCode(buffer.getInt(body + 8, ByteOrder.LITTLE_ENDIAN))
                        + " command=" + buffer.getLong(offset + 24, ByteOrder.LITTLE_ENDIAN));
            int data = body + CoreProtocol.RESPONSE_FIXED_PAYLOAD_LENGTH;
            int dataLength = buffer.getInt(body + 32, ByteOrder.LITTLE_ENDIAN);
            if (trading) {
                if (batchSize > 1) {
                    if (buffer.getInt(data + 4, ByteOrder.LITTLE_ENDIAN) != batchSize)
                        throw new IllegalStateException("wrong terminal batch count");
                    int cursor = data + 8;
                    for (int item = 0; item < batchSize; item++) {
                        int size = buffer.getInt(cursor, ByteOrder.LITTLE_ENDIAN), start = cursor + 4;
                        if (size < 44 || size > data + dataLength - start
                                || buffer.getInt(start, ByteOrder.LITTLE_ENDIAN) != item
                                || buffer.getInt(start + 28, ByteOrder.LITTLE_ENDIAN) != ResponseStatus.APPLIED.wireCode())
                            throw new IllegalStateException("non-applied or malformed batch item index=" + item
                                    + " status=" + buffer.getInt(start + 28, ByteOrder.LITTLE_ENDIAN)
                                    + " code=" + CoreResultCode.fromWireCode(buffer.getInt(start + 32, ByteOrder.LITTLE_ENDIAN))
                                    + " orderId=" + buffer.getLong(start + 4, ByteOrder.LITTLE_ENDIAN)
                                    + " command=" + buffer.getLong(offset + 24, ByteOrder.LITTLE_ENDIAN));
                        cursor = start + size;
                    }
                    if (cursor != data + dataLength) throw new IllegalStateException("trailing batch result bytes");
                }
                tradingMessages++;
            } else if (readUser) {
                userData = new byte[dataLength]; buffer.getBytes(data, userData);
            }
            received++;
            return length;
        }
    }
}
