package com.surprising.aeron.service.core;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.snapshot.state.FundsStateHash;
import com.surprising.product.api.ProductLine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交易核心的十条黄金轨迹：在结构重构之前把「行为不变」固化成可重复对比的字节证据。
 *
 * <p>本类不新建执行框架，只串起现有夹具：{@link TradingCoreRuntime} 起单产品线运行时、
 * {@link CoreTestCompletion} 推进命令到终态、{@code CoreMessageCodec}/{@code CoreProtocol} 取真实协议字节、
 * {@link FundsStateHash} 与 {@code TradingCoreRuntime.stateHash()} 取两个既有哈希。
 *
 * <p>每条轨迹记录五项：
 * <ul>
 *   <li>{@code input}：完整输入帧（{@code CoreMessageCodec.encode}）的十六进制；
 *       快照恢复一条例外，输入是快照字节的 SHA-256 与长度，另记 {@code tailInput}。
 *       快照内层是字段编号自描述格式，字段重排仍恢复同一状态，因此不把快照字节本身当作黄金。</li>
 *   <li>{@code response}：生产协议响应负载（{@code CoreProtocol.responsePayload}）的十六进制，
 *       含 status / commandStatus / resultCode / committedCoreSequence / appliedCommandCount / data。</li>
 *   <li>{@code stateHash}：{@code TradingCoreRuntime.stateHash()}。</li>
 *   <li>{@code fundsHash}：{@code FundsStateHash.compute(tradingState())}。</li>
 *   <li>{@code state}：关键账户 / 订单 / 持仓 / 财库快照的规范化文本（UTF-8 后十六进制），
 *       所有映射按键排序。两个哈希都不覆盖持仓与订单明细，这一项用来补足。</li>
 * </ul>
 *
 * <p>确定性来源全部固定：单产品线 {@code LINEAR_PERPETUAL}、固定业务时间、固定 UUID 前缀、
 * 按场景与序号派生的命令编号、固定 sourceId/sourceSequence/correlationId，且只使用同步执行模式，
 * 不加载 {@code PipelinedExecutionExtension}、不启动真实 Aeron Cluster。
 *
 * <p>默认只与提交的基线 {@code src/test/resources/aeron-core-golden-trace-v1.txt} 对比；
 * 需要重新录制时显式执行 {@code -Daeron.golden.record=true}，避免重构把基线自动改写掉。
 */
class AeronCoreGoldenTraceTest {

    private static final ProductLine LINE = ProductLine.LINEAR_PERPETUAL;
    private static final long TIME = 1_700_000_000_000L;
    private static final long GOLDEN_MSB = 0x474f4c44454e0000L;
    private static final long SOURCE_ID = 991L;
    private static final String RESOURCE = "/aeron-core-golden-trace-v1.txt";
    private static final Path BASELINE_PATH = Path.of("src/test/resources/aeron-core-golden-trace-v1.txt");
    private static final String HEADER = "# Aeron core golden trace v1\n"
            + "# 重新录制: mvn ... -Daeron.golden.record=true\n"
            + "# 字段定义见 AeronCoreGoldenTraceTest 类注释；除快照恢复外 input 为完整输入帧 hex。\n";

    private static final String[] STEP_IDS = {
            "01-limit-place", "02-taker-fill", "03-cancel", "04-amend", "05-batch-place",
            "06-batch-cancel", "07-liquidation", "08-funding", "09-transfer", "10-snapshot-restore"
    };

    /**
     * 同输入连续跑两遍，要求每条轨迹的响应字节、两个哈希和状态快照逐字段完全一致；再与已提交基线对比。
     * 任何结构重构都必须让本测试保持通过，否则说明行为发生了变化。
     */
    @Test
    void tenTracesAreByteIdenticalAcrossRunsAndMatchCommittedBaseline() throws Exception {
        Map<String, String> first = recordAll();
        Map<String, String> second = recordAll();

        for (String step : STEP_IDS) {
            for (String field : new String[]{"input", "response", "stateHash", "fundsHash", "state"}) {
                assertThat(first.containsKey(step + "." + field)).as("%s.%s 已记录", step, field).isTrue();
                assertThat(first.get(step + "." + field)).as("%s.%s 非空", step, field).isNotBlank();
            }
        }
        assertSame("同输入第二次运行", first, second);

        if (Boolean.getBoolean("aeron.golden.record")) {
            Files.writeString(BASELINE_PATH, render(first), StandardCharsets.UTF_8);
            return;
        }
        assertSame("已提交基线", readBaseline(), first);
    }

    // ---------------------------------------------------------------- 十条轨迹

    /** 限价挂单：注册标的、注资后挂一笔 GTC 卖单。 */
    private static Map<String, String> limitPlace() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(1);
            setup(commands, runtime);
            CoreMessage target = commands.message(11, CoreMessageType.PLACE_ORDER,
                    encodePlace(101, CoreOrderSide.SELL, 100, 10));
            return capture(runtime, target, submit(runtime, target));
        }
    }

    /** 吃单成交：先挂卖单，再用同价买单吃掉其中四份。 */
    private static Map<String, String> takerFill() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(2);
            setup(commands, runtime);
            submit(runtime, commands.message(11, CoreMessageType.PLACE_ORDER,
                    encodePlace(101, CoreOrderSide.SELL, 100, 10)));
            CoreMessage target = commands.message(22, CoreMessageType.PLACE_ORDER,
                    encodePlace(102, CoreOrderSide.BUY, 100, 4));
            return capture(runtime, target, submit(runtime, target));
        }
    }

    /** 撤单：撤掉准备阶段挂出的独立订单 901。 */
    private static Map<String, String> cancel() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(3);
            setup(commands, runtime);
            submit(runtime, commands.message(11, CoreMessageType.PLACE_ORDER,
                    encodePlace(901, CoreOrderSide.SELL, 105, 2)));
            CoreMessage target = commands.message(11, CoreMessageType.CANCEL_ORDER,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(901)));
            return capture(runtime, target, submit(runtime, target));
        }
    }

    /** 改单：用字段补丁把订单 101 改成 103，同时改价与改量。 */
    private static Map<String, String> amend() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(4);
            setup(commands, runtime);
            submit(runtime, commands.message(11, CoreMessageType.PLACE_ORDER,
                    encodePlace(101, CoreOrderSide.SELL, 100, 10)));
            CoreMessage target = commands.message(11, CoreMessageType.AMEND_ORDER,
                    TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(
                            101, 103, "golden-amended", 95L, 8L, CoreTimeInForce.GTC, false)));
            return capture(runtime, target, submit(runtime, target));
        }
    }

    /** 批量下单：一次提交两笔不同价位的买单。 */
    private static Map<String, String> batchPlace() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(5);
            setup(commands, runtime);
            CoreMessage target = commands.message(22, CoreMessageType.PLACE_ORDER_BATCH,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(201, CoreOrderSide.BUY, 90, 1),
                            place(202, CoreOrderSide.BUY, 80, 1)))));
            return capture(runtime, target, submit(runtime, target));
        }
    }

    /** 批量撤单：一次撤销准备阶段批量挂出的两笔买单。 */
    private static Map<String, String> batchCancel() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(6);
            setup(commands, runtime);
            submit(runtime, commands.message(22, CoreMessageType.PLACE_ORDER_BATCH,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            place(201, CoreOrderSide.BUY, 90, 1),
                            place(202, CoreOrderSide.BUY, 80, 1))))));
            CoreMessage target = commands.message(22, CoreMessageType.CANCEL_ORDER_BATCH,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(201), new CancelOrderCommand(202)))));
            return capture(runtime, target, submit(runtime, target));
        }
    }

    /** 强平：标记价暴跌、扫出强平计划后执行整批强平。 */
    private static Map<String, String> liquidation() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(7);
            submit(runtime, commands.message(0, CoreMessageType.REGISTER_INSTRUMENT, registerInstrument()));
            markPrice(commands, runtime, 100, 1);
            deposit(commands, runtime, 1, 110);
            deposit(commands, runtime, 2, 10_000);
            deposit(commands, runtime, 7, 110);
            submit(runtime, commands.message(2, CoreMessageType.PLACE_ORDER,
                    encodePlace(100, CoreOrderSide.SELL, 100, 20)));
            for (long user : new long[]{1, 7}) {
                submit(runtime, commands.message(user, CoreMessageType.PLACE_ORDER,
                        encodePlace(100 + user, CoreOrderSide.BUY, 100, 10)));
                for (int index = 0; index < 2; index++) {
                    submit(runtime, commands.message(user, CoreMessageType.PLACE_ORDER,
                            encodePlace(200 + user * 10 + index, CoreOrderSide.SELL, 110, 1, true)));
                }
            }
            markPrice(commands, runtime, 1, 2);
            for (int polls = 0; runtime.runtimeState.firstRiskIncompleteScan() != null; polls++) {
                assertThat(polls).as("风险扫描必须推进").isLessThan(20);
                submit(runtime, commands.message(0, CoreMessageType.CONTINUE_RISK_SCAN,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
            }
            var batch = ExecuteLiquidationBatchCommand.fromWork(liquidationWork(runtime, commands), 0, 0);
            CoreMessage target = commands.message(0, CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                    TradingCommandCodec.encodeExecuteLiquidationBatch(batch));
            return capture(runtime, target, submit(runtime, target));
        }
    }

    /** 资金费：有持仓、有标记价时执行一轮资金费结算。 */
    private static Map<String, String> funding() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(8);
            setup(commands, runtime);
            submit(runtime, commands.message(11, CoreMessageType.PLACE_ORDER,
                    encodePlace(101, CoreOrderSide.SELL, 100, 10)));
            submit(runtime, commands.message(22, CoreMessageType.PLACE_ORDER,
                    encodePlace(102, CoreOrderSide.BUY, 100, 4)));
            CoreMessage target = commands.message(0, CoreMessageType.APPLY_FUNDING,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(900, "1", 10_000, 0, 256)));
            return capture(runtime, target, submit(runtime, target));
        }
    }

    /** 划转：单运行时内发起一笔转出，目标产品线与入账步骤不在此轨迹内。 */
    private static Map<String, String> transfer() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(9);
            deposit(commands, runtime, 7, 100);
            ProductLine target = ProductLine.SPOT;
            var transfer = new TransferFundsCommand(91, LINE, target,
                    LINE.accountTypeCode(), target.accountTypeCode(), "USDT", 30,
                    "golden-transfer-91", "", 7, 22);
            CoreMessage targetMessage = commands.message(7, CoreMessageType.TRANSFER_OUT,
                    TradingCommandCodec.encodeTransferFunds(transfer));
            return capture(runtime, targetMessage, submit(runtime, targetMessage));
        }
    }

    /** 快照到恢复：记录恢复实例上的哈希、状态，以及恢复后继续下单的响应字节。 */
    private static Map<String, String> snapshotRestore() {
        try (var runtime = new TradingCoreRuntime(LINE)) {
            var commands = new Commands(10);
            setup(commands, runtime);
            submit(runtime, commands.message(11, CoreMessageType.PLACE_ORDER,
                    encodePlace(101, CoreOrderSide.SELL, 100, 10)));
            submit(runtime, commands.message(22, CoreMessageType.PLACE_ORDER,
                    encodePlace(102, CoreOrderSide.BUY, 100, 4)));
            byte[] snapshot = runtime.snapshot(100);
            try (var restored = TradingCoreRuntime.fromSnapshot(LINE, snapshot)) {
                CoreMessage tail = commands.message(22, CoreMessageType.PLACE_ORDER,
                        encodePlace(103, CoreOrderSide.BUY, 100, 2));
                CoreResponse response = submit(restored, tail);
                Map<String, String> fields = capture(restored, tail, response);
                fields.put("input", "sha256:" + sha256(snapshot) + ":len=" + snapshot.length);
                fields.put("tailInput", hex(CoreMessageCodec.encode(tail)));
                return fields;
            }
        }
    }

    private static Map<String, String> recordAll() {
        Map<String, String> all = new LinkedHashMap<>();
        put(all, STEP_IDS[0], limitPlace());
        put(all, STEP_IDS[1], takerFill());
        put(all, STEP_IDS[2], cancel());
        put(all, STEP_IDS[3], amend());
        put(all, STEP_IDS[4], batchPlace());
        put(all, STEP_IDS[5], batchCancel());
        put(all, STEP_IDS[6], liquidation());
        put(all, STEP_IDS[7], funding());
        put(all, STEP_IDS[8], transfer());
        put(all, STEP_IDS[9], snapshotRestore());
        return all;
    }

    // ---------------------------------------------------------------- 夹具串接

    /** 注册测试标的、提交参考价、给两个用户注资；所有命令使用确定性编号。 */
    private static void setup(Commands commands, TradingCoreRuntime runtime) {
        submit(runtime, commands.message(0, CoreMessageType.REGISTER_INSTRUMENT, registerInstrument()));
        markPrice(commands, runtime, 100, 1);
        deposit(commands, runtime, 11, 20_000);
        deposit(commands, runtime, 22, 20_000);
    }

    private static byte[] registerInstrument() {
        var instrument = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(100_000, 1_000_000_000_000L / 100_000,
                        Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, 1_000_000_000_000L / 100_000,
                                100_000, 50_000))),
                0, 0, 0, -1, 0);
        return TradingCommandCodec.encodeRegisterInstrument(instrument, LINE);
    }

    private static void markPrice(Commands commands, TradingCoreRuntime runtime, long price, long sequence) {
        submit(runtime, commands.message(0, CoreMessageType.APPLY_MARK_PRICE,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("1", price, sequence, TIME), LINE)));
    }

    private static void deposit(Commands commands, TradingCoreRuntime runtime, long userId, long amount) {
        submit(runtime, commands.message(userId, CoreMessageType.ADJUST_BALANCE,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", amount))));
    }

    private static CoreLiquidationWorkView liquidationWork(TradingCoreRuntime runtime, Commands commands) {
        CoreMessage query = commands.message(0, CoreMessageType.LIQUIDATION_WORK_QUERY,
                CoreLiquidationWorkCodec.encodeQuery(LINE, CoreLiquidationWorkView.Purpose.EXECUTION,
                        0, 1000, 1_048_576));
        return CoreLiquidationWorkCodec.decodeWork(submit(runtime, query).data());
    }

    /**
     * 走现有测试完成辅助器取得终态；查询走同步查询入口，其余走异步作用域完整推进。
     * 显式要求命令真正被应用，避免录到一条「确定但被拒」的假轨迹。
     */
    private static CoreResponse submit(TradingCoreRuntime runtime, CoreMessage message) {
        CoreMessageType type = message.header().messageType();
        boolean query = type == CoreMessageType.LIQUIDATION_WORK_QUERY;
        CoreResponse response = query
                ? CoreTestCompletion.querySynchronously(runtime, message)
                : CoreTestCompletion.applyAsynchronously(runtime, message);
        assertThat(response).as("%s 必须产生终态响应", type).isNotNull();
        assertThat(response.status()).as("%s 必须成功，结果码 %s", type, response.resultCode())
                .isIn(ResponseStatus.APPLIED, ResponseStatus.OK);
        if (!query) {
            assertThat(response.commandStatus()).as("%s 必须真正应用，结果码 %s", type, response.resultCode())
                    .isEqualTo(ResponseStatus.APPLIED);
        }
        return response;
    }

    private static PlaceOrderCommand place(long id, CoreOrderSide side, long price, long quantity) {
        return new PlaceOrderCommand(id, "1", side, price, quantity, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "golden-" + id);
    }

    private static byte[] encodePlace(long id, CoreOrderSide side, long price, long quantity) {
        return TradingCommandCodec.encodePlaceOrder(place(id, side, price, quantity));
    }

    private static byte[] encodePlace(long id, CoreOrderSide side, long price, long quantity, boolean reduceOnly) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(id, "1", side, price, quantity,
                reduceOnly, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "golden-" + id));
    }

    /** 按场景和递增序号派生确定性命令编号，避免随机 UUID 破坏跨轮次一致性。 */
    private static final class Commands {
        private final int scenario;
        private long sequence;

        Commands(int scenario) {
            this.scenario = scenario;
        }

        CoreMessage message(long userId, CoreMessageType type, byte[] payload) {
            long next = ++sequence;
            UUID id = new UUID(GOLDEN_MSB, ((long) scenario << 32) | next);
            CoreMessageHeader header = type == CoreMessageType.LIQUIDATION_WORK_QUERY
                    ? CoreMessageHeader.query(type, id, LINE, CommandSource.OPERATIONS,
                            SOURCE_ID, next, userId, TIME, next)
                    : CoreMessageHeader.command(type, id, LINE, CommandSource.OPERATIONS,
                            SOURCE_ID, next, userId, TIME, next);
            return new CoreMessage(header, payload);
        }
    }

    // ---------------------------------------------------------------- 记录与对比

    /** 把一条命令的五项证据规范化为字符串字段。 */
    private static Map<String, String> capture(TradingCoreRuntime runtime, CoreMessage input, CoreResponse response) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("input", hex(CoreMessageCodec.encode(input)));
        fields.put("response", hex(CoreProtocol.responsePayload(response)));
        fields.put("stateHash", Long.toHexString(runtime.stateHash()));
        TradingCoreState state = runtime.tradingState();
        fields.put("fundsHash", Long.toHexString(FundsStateHash.compute(state)));
        fields.put("state", hex(stateText(state).getBytes(StandardCharsets.UTF_8)));
        return fields;
    }

    private static void put(Map<String, String> all, String step, Map<String, String> fields) {
        fields.forEach((field, value) -> all.put(step + "." + field, value));
    }

    /**
     * 关键账户、订单、持仓与财库快照的规范化文本。所有映射按键排序，避免 HashMap 迭代顺序影响结果；
     * 两个既有哈希都不覆盖订单与持仓明细，这一项用于补足资金、冻结与终态证据。
     */
    private static String stateText(TradingCoreState state) {
        StringBuilder text = new StringBuilder();
        text.append("productLine=").append(state.productLine()).append('\n');
        text.append("revision=").append(state.revision()).append('\n');
        text.append("users=").append(state.users().size()).append('\n');
        for (var entry : new TreeMap<>(state.users()).entrySet()) {
            var user = entry.getValue();
            text.append("user ").append(entry.getKey()).append(' ').append(user.revision())
                    .append(' ').append(user.positionMode()).append('\n');
            for (var balance : new TreeMap<>(user.balances()).values()) {
                text.append("  balance ").append(balance.asset()).append(' ')
                        .append(balance.availableUnits()).append(' ').append(balance.lockedUnits()).append('\n');
            }
            for (var position : new TreeMap<>(user.positions()).entrySet()) {
                var value = position.getValue();
                text.append("  position ").append(position.getKey()).append(' ')
                        .append(value.instrumentId()).append(' ').append(value.marginAsset()).append(' ')
                        .append(value.marginMode()).append(' ').append(value.positionSide()).append(' ')
                        .append(value.signedQuantitySteps()).append(' ').append(value.entryPriceTicks()).append(' ')
                        .append(value.entryValueTicks()).append(' ').append(value.realizedPnlUnits()).append(' ')
                        .append(value.positionMarginUnits()).append('\n');
            }
            for (Long orderId : new TreeSet<>(user.reservations().keySet())) {
                text.append("  reservation ").append(orderId).append('\n');
            }
        }
        text.append("orders=").append(state.orders().size()).append('\n');
        for (var entry : new TreeMap<>(state.orders()).entrySet()) {
            var order = entry.getValue();
            text.append("order ").append(entry.getKey()).append(' ').append(order.userId()).append(' ')
                    .append(order.side()).append(' ').append(order.matchingPriceTicks()).append(' ')
                    .append(order.quantitySteps()).append(' ').append(order.executedQuantitySteps()).append(' ')
                    .append(order.cumulativeFeeUnits()).append(' ').append(order.status()).append(' ')
                    .append(order.revision()).append(" [").append(order.clientOrderId()).append("] ")
                    .append(order.commandId()).append(' ').append(order.createdAtEpochMillis()).append(' ')
                    .append(order.updatedAtEpochMillis()).append(' ').append(order.clusterPosition()).append('\n');
        }
        var treasury = state.treasuryState();
        appendTreasury(text, "fee", treasury.feeBalances());
        appendTreasury(text, "insurance", treasury.insuranceBalances());
        appendTreasury(text, "deficit", treasury.insuranceDeficits());
        appendTreasury(text, "liquidationFee", treasury.liquidationFeeBalances());
        appendTreasury(text, "fundingResidual", treasury.fundingResidualBalances());
        appendTreasury(text, "roundingResidual", treasury.roundingResidualBalances());
        appendTreasury(text, "clearingPnl", treasury.clearingPnlBalances());
        return text.toString();
    }

    private static void appendTreasury(StringBuilder text, String name, Map<String, Long> values) {
        text.append("treasury ").append(name).append(' ').append(values.size()).append('\n');
        for (var entry : new TreeMap<>(values).entrySet()) {
            text.append("  ").append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    /** 状态字段是规范化文本的十六进制，失败时解码回明文，方便直接定位哪个账户或订单变了。 */
    private static void assertSame(String label, Map<String, String> expected, Map<String, String> actual) {
        assertThat(actual.keySet()).as("%s：轨迹字段集合", label).isEqualTo(expected.keySet());
        for (String key : expected.keySet()) {
            String wanted = expected.get(key);
            String found = actual.get(key);
            if (key.endsWith(".state")) {
                assertThat(found).as("%s：%s%n期望状态:%n%s%n实际状态:%n%s", label, key,
                        new String(HexFormat.of().parseHex(wanted), StandardCharsets.UTF_8),
                        new String(HexFormat.of().parseHex(found), StandardCharsets.UTF_8)).isEqualTo(wanted);
            } else {
                assertThat(found).as("%s：%s", label, key).isEqualTo(wanted);
            }
        }
    }

    private static Map<String, String> readBaseline() throws IOException {
        try (InputStream in = AeronCoreGoldenTraceTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as("已提交的黄金轨迹基线 " + RESOURCE).isNotNull();
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> parse(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            assertThat(separator).as("基线行格式 key=value: %s", line).isPositive();
            values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return values;
    }

    private static String render(Map<String, String> values) {
        StringBuilder content = new StringBuilder(HEADER);
        values.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
        return content.toString();
    }
}
