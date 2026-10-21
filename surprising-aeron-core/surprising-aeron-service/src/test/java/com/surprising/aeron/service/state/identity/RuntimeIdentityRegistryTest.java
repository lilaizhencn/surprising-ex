package com.surprising.aeron.service.state.identity;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份表的确定性键、稠密编号、引用保留及快照恢复测试。显式区分独立冷表、普通并发读取线程与真实账户运行时；这些用例不代表交易集群吞吐或碰撞概率证明。
 */
class RuntimeIdentityRegistryTest {
    /**
     * 验证永久大整数 ID 仍分配内部零号，未知查询用负一或空表示；快照保留规范数字文本，恢复后编号与后续分配稳定。
     */
    @Test
    void nativePermanentIdsKeepDenseZeroAndSnapshotProtocolSpellings() {
        // 本用例当前验证的独立身份表，或原表与恢复表遍历中的当前实例
        var registry = new RuntimeIdentityRegistry();
        assertThat(registry.symbolId(Integer.MAX_VALUE)).isZero();
        assertThat(registry.symbolId("2147483647")).isZero();
        assertThat(registry.symbolId(10000)).isEqualTo(1);
        assertThat(registry.findSymbolIdValue(Integer.MAX_VALUE)).isZero();
        assertThat(registry.findSymbolIdValue(9999)).isEqualTo(-1);
        assertThat(registry.findSymbolId("9999")).isNull();
        // 导出的身份快照，检查永久标的文本与内部编号关系
        var snapshot = registry.snapshot();
        assertThat(snapshot.symbolIds()).containsEntry("2147483647", 0).containsEntry("10000", 1);
        // 从快照恢复的独立身份表，释放它不影响原表
        var restored = RuntimeIdentityRegistry.restore(snapshot);
        assertThat(restored.symbolId(Integer.MAX_VALUE)).isZero();
        assertThat(restored.symbolId(10000)).isEqualTo(1);
        assertThat(restored.symbolId(9999)).isEqualTo(2);
        assertThat(restored.instrumentId(0)).isEqualTo("2147483647");
    }

    /**
     * Owner 用带方向原名准备持仓，再由单线程执行器中的独立 Lane 以结构化标的、方向保留；验证是同一身份并完成引用释放。
     */
    @Test
    void preparedDerivativePositionProbeMatchesStringIdentityWithoutRebuildingItsName() throws Exception {
        // 本用例当前验证的独立身份表，或原表与恢复表遍历中的当前实例
        var registry = new RuntimeIdentityRegistry();
        // 本用例身份所属用户十七
        long user = 17;
        // 决定用户账户 Lane 的固定拓扑
        var topology = LaneTopology.configured(false);
        // 按测试用户路由构造或从冷运行时取得的所属账户 Lane
        var lane = new AccountLaneState(topology.accountLaneId(user), 16);
        // 独立算出的预期哈希，或 Owner 已准备的预期持仓键
        long expected = registry.positionKey(user, "1:LONG");
        try (/* 模拟独立 Lane 操作或普通冷读的单线程执行器，用例结束关闭 */ var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            // 工作线程读到或保留的持仓键，必须等于原预期键
            long actual = executor.submit(() -> registry.retainPositionInLane(
                    lane, user, "1", CorePositionSide.LONG)).get();
            assertThat(actual).isEqualTo(expected);
            assertThat(registry.preparedPositionKey(user, "1", CorePositionSide.LONG))
                    .isEqualTo(expected);
            registry.releasePublishedPosition(actual);
            registry.releasePositionKey(actual);
        }
    }

    /**
     * 检查两份发布引用未消费前不能退休身份，并在最后消费后核对快照仍能恢复该存活身份。
     */
    @Test
    void lanePositionIdentitySurvivesEarlierCommitRetirementUntilItsOutputIsPublished() throws Exception {
        verifyPositionRetention(true);
    }

    /**
     * 不经过快照，检查发布引用保护、最终退休及同名确定性重建。
     */
    @Test
    void lanePositionIdentityRetentionWithoutSnapshot() throws Exception {
        verifyPositionRetention(false);
    }

    /**
     * 用单线程执行器替独立 Lane 保留两份持仓身份；逐次消费和尝试退休，验证最后消费前身份存活，最终可删除并以相同键重建。可选快照只检查存活身份恢复，不复制发布引用数。
     * @param snapshot 是否在最后一份发布引用消费后、身份退休前额外核对快照恢复
     */
    private void verifyPositionRetention(boolean snapshot) throws Exception {
        // 本用例当前验证的独立身份表，或原表与恢复表遍历中的当前实例
        var registry = new RuntimeIdentityRegistry();
        registry.assertOwner();
        // 本用例身份所属用户十七
        long user = 17;
        // 按测试用户路由构造或从冷运行时取得的所属账户 Lane
        var lane = new AccountLaneState(LaneTopology.configured(false).accountLaneId(user), 16);
        try (/* 模拟独立 Lane 操作或普通冷读的单线程执行器，用例结束关闭 */ var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            // 本用例反复保留、发布消费和退休的确定性持仓键
            long key = executor.submit(() -> registry.retainPositionInLane(lane, user, "1:LONG")).get();
            registry.releasePositionKey(key);
            assertThat(registry.positionKey(user, key)).isEqualTo("1:LONG");
            executor.submit(() -> registry.retainPositionInLane(lane, user, "1:LONG")).get();
            registry.releasePublishedPosition(key);
            registry.releasePositionKey(key);
            assertThat(registry.findPositionKey(user, "1:LONG")).isEqualTo(key);
            registry.releasePublishedPosition(key);
            if (snapshot) {
                // 从快照恢复的独立身份表，释放它不影响原表
                var restored = RuntimeIdentityRegistry.restore(registry.snapshot());
                assertThat(restored.positionKey(user, key)).isEqualTo("1:LONG");
            }
            registry.releasePositionKey(key);
            assertThat(registry.findPositionKey(user, "1:LONG")).isNull();
            // 最后删除后重新准备同名身份所得结果；客户端结果保留新分配符号
            long recreated = executor.submit(() -> registry.retainPositionInLane(lane, user, "1:LONG")).get();
            assertThat(recreated).isEqualTo(key);
            registry.releasePublishedPosition(key);
            registry.releasePositionKey(key);
            assertThat(registry.findPositionKey(user, "1:LONG")).isNull();
            if (snapshot) assertThat(registry.snapshot().positionKeys()).isEmpty();
        }
    }

    /**
     * 冷表两次准备同一客户端单号，逐次 Lane 释放只在最后删除；恢复表可独立释放而不影响原表，错误 Lane 拒绝。
     */
    @Test
    void laneReleaseCompletesWithoutAnOwnerHandoffAndRestoresLiveKeys() {
        // 本用例当前验证的独立身份表，或原表与恢复表遍历中的当前实例
        var registry = new RuntimeIdentityRegistry();
        // 本用例身份所属用户十七
        long user = 17;
        // 按测试用户路由构造或从冷运行时取得的所属账户 Lane
        var lane = new AccountLaneState(LaneTopology.configured(false).accountLaneId(user), 16);
        // 首次客户端准备结果，或第一个用户/名称的实际持仓键
        var first = registry.prepareClientKeyInLane(lane, user, "客户-retired");
        registry.prepareClientKeyInLane(lane, user, "客户-retired");
        // 去掉首次客户端准备结果符号后的实际正键
        long firstKey = RuntimeIdentityRegistry.clientKeyValue(first);
        registry.releaseClientKeyInLane(lane, user, firstKey);
        assertThat(registry.findClientKey(user, "客户-retired")).isEqualTo(firstKey);
        registry.releaseClientKeyInLane(lane, user, firstKey);
        assertThat(registry.clientIdentityCount()).isZero();
        // 最后删除后重新准备同名身份所得结果；客户端结果保留新分配符号
        var recreated = registry.prepareClientKeyInLane(lane, user, "客户-retired");
        // 从快照恢复的独立身份表，释放它不影响原表
        var restored = RuntimeIdentityRegistry.restore(registry.snapshot());
        // 去掉重新准备客户端结果符号后的实际正键
        long recreatedKey = RuntimeIdentityRegistry.clientKeyValue(recreated);
        restored.releaseClientKeyInLane(lane, user, recreatedKey);
        assertThat(restored.clientIdentityCount()).isZero();
        assertThat(registry.clientIdentityCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.releaseClientKeyInLane(
                new AccountLaneState(lane.laneId() + 1, 16), user, recreatedKey))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 在原始及恢复冷表中检查异线程只读反查、跨用户误释放和逐用户删除互不干扰；未启动真实账户工作线程。
     */
    @Test
    void clientReadOnlyLookupAndLaneReleasePreserveOtherKeysAfterRestore() throws Exception {
        // 供快照与恢复对照的原始冷客户端身份表
        var original = new RuntimeIdentityRegistry();
        // 首次客户端准备结果，或第一个用户/名称的实际持仓键
        long first = original.clientKey(7, "客户-A");
        // 另一用户或另一名称的身份键，用于检查释放隔离
        long second = original.clientKey(8, "客户-B");
        for (var /* 本用例当前验证的独立身份表，或原表与恢复表遍历中的当前实例 */ registry : new RuntimeIdentityRegistry[]{original, RuntimeIdentityRegistry.restore(original.snapshot())}) {
            assertThat(registry.clientOrderId(7, first)).isEqualTo("客户-A");
            assertThat(registry.clientOrderId(8, second)).isEqualTo("客户-B");
            // 模拟独立 Lane 操作或普通冷读的单线程执行器，用例结束关闭
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                assertThat(executor.submit(() -> registry.clientOrderId(7, first)).get()).isEqualTo("客户-A");
            } finally { executor.shutdownNow(); }
            registry.releaseClientKey(8, first);
            assertThat(registry.findClientKey(7, "客户-A")).isEqualTo(first);
            registry.releaseClientKey(7, first);
            assertThat(registry.findClientKey(7, "客户-A")).isNull();
            assertThat(registry.clientOrderId(8, second)).isEqualTo("客户-B");
            registry.releaseClientKey(8, second);
            assertThat(registry.clientIdentityCount()).isZero();
        }
    }

    /**
     * 把身份绑定冷运行时，重复准入只新分配一次；先回滚重复引用保留首份，再回滚首份清空，错误 Lane 不能准备身份。
     */
    @Test
    void laneOwnsPreparationAndDuplicateRollbackPreservesLiveIdentity() {
        // 本用例 Owner 准备和查询的身份表
        var identities = new RuntimeIdentityRegistry();
        // 当前身份所属用户；哈希参考计算中为随机非负用户
        long userId = 17;
        // 决定用户账户 Lane 的固定拓扑
        var topology = LaneTopology.configured(false);
        try (/* 仅供身份归属测试的冷账户运行时，资源作用域退出时关闭 */ var runtime = new TradingRuntimeState(topology)) {
            identities.bindClientLanes(runtime);
            // 按测试用户路由构造或从冷运行时取得的所属账户 Lane
            var lane = runtime.accountLane(topology.accountLaneId(userId));
            // 首次客户端准备结果，或第一个用户/名称的实际持仓键
            var first = identities.prepareClientKeyInLane(lane, userId, "客户😀");
            // 同名客户端第二次准备结果，代表额外保留的一份引用
            var duplicate = identities.prepareClientKeyInLane(lane, userId, "客户😀");
            assertThat(RuntimeIdentityRegistry.clientKeyValue(duplicate))
                    .isEqualTo(RuntimeIdentityRegistry.clientKeyValue(first));
            assertThat(lane.clientIdentityAllocations()).isEqualTo(1);
            identities.rollbackClientKeyInLane(lane, userId, "客户😀", duplicate);
            assertThat(identities.clientIdentityCount()).isEqualTo(1);
            identities.rollbackClientKeyInLane(lane, userId, "客户😀", first);
            assertThat(identities.clientIdentityCount()).isZero();
            // 与用户所属 Lane 不同的独立 Lane，准备应拒绝
            var wrongLane = new AccountLaneState(lane.laneId() + 1, 16);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    identities.prepareClientKeyInLane(wrongLane, userId, "wrong"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("crossed account Lane");
            assertThat(identities.clientIdentityCount()).isZero();
        }
    }

    /**
     * 用反射调用流式哈希，与 Java UTF-8 字节编码独立计算的结果逐一比较；覆盖全部单字符、代理异常及固定种子随机串，不测吞吐。
     */
    @Test
    void streamedIdentityHashExactlyMatchesJavaUtf8IncludingMalformedSurrogates() throws Exception {
        // 允许反射调用的私有确定性哈希方法
        var method = RuntimeIdentityRegistry.class.getDeclaredMethod("deterministicKey", long.class, String.class);
        method.setAccessible(true);
        // 原始样例、全部单字符及随机串组成的编码测试集合
        var values = new java.util.ArrayList<String>();
        values.addAll(java.util.List.of("", "client-123", "客户😀", "\ud800", "\udc00", "\ud800x\udc00", "\ud800\ud800\udc00"));
        for (/* 遍历全部 UTF-16 单代码单元的整数值 */ int c = 0; c <= Character.MAX_VALUE; c++) values.add(String.valueOf((char)c));
        // 固定种子九十一的伪随机源，保证测试可复现
        var random = new java.util.Random(91);
        for (/* 当前生成的随机字符串序号 */ int i = 0; i < 1_000; i++) {
            // 当前十六个随机 UTF-16 单元组成的字符数组
            char[] chars = new char[16];
            for (/* 当前随机字符在数组中的下标 */ int j = 0; j < chars.length; j++) chars[j] = (char)random.nextInt(65536);
            values.add(new String(chars));
        }
        for (String /* 本次规范化或 UTF-8 哈希验证所用的原文本 */ value : values) {
            // 当前身份所属用户；哈希参考计算中为随机非负用户
            long userId = random.nextLong() & Long.MAX_VALUE;
            // 独立算出的预期哈希，或 Owner 已准备的预期持仓键
            long expected = 0xcbf29ce484222325L;
            for (/* 参考哈希中用户 long 低位字节在前的比特偏移 */ int shift = 0; shift < 64; shift += 8)
                expected = (expected ^ (userId >>> shift & 255)) * 0x100000001b3L;
            for (byte /* Java 标准 UTF-8 编码产生的当前字节 */ b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                expected = (expected ^ (b & 255)) * 0x100000001b3L;
            expected &= Long.MAX_VALUE;
            assertThat((long)method.invoke(null, userId, value)).isEqualTo(expected == 0 ? 1 : expected);
        }
    }

    /**
     * 用 Java 字符串哈希相同的 Aa、BB 检查身份仍按完整文本区分，并检查用户隔离、中文补充字符、恢复及单项释放。
     */
    @Test
    void positionLookupSeparatesEqualHashesAndSurvivesRestoreAndRelease() {
        // 本用例 Owner 准备和查询的身份表
        var identities = new RuntimeIdentityRegistry();
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        // 首次客户端准备结果，或第一个用户/名称的实际持仓键
        long first = identities.positionKey(7, "Aa");
        // 另一用户或另一名称的身份键，用于检查释放隔离
        long second = identities.positionKey(7, "BB");
        // 另一用户相同名称的持仓键，验证用户维度隔离
        long third = identities.positionKey(8, "Aa");
        // 含中文与补充平面字符的原持仓名对应键
        long unicode = identities.positionKey(7, "客户😀:NET");
        for (var /* 本用例当前验证的独立身份表，或原表与恢复表遍历中的当前实例 */ registry : new RuntimeIdentityRegistry[]{identities, RuntimeIdentityRegistry.restore(identities.snapshot())}) {
            assertThat(registry.positionKey(7, new String("Aa"))).isEqualTo(first);
            assertThat(registry.preparedPositionKey(7, "BB")).isEqualTo(second).isNotEqualTo(first);
            assertThat(registry.preparedPositionKey(8, "Aa")).isEqualTo(third).isNotEqualTo(first);
            assertThat(registry.findPositionKey(7, "客户😀:NET")).isEqualTo(unicode);
            registry.releasePositionKey(first);
            assertThat(registry.findPositionKey(7, "Aa")).isNull();
            assertThat(registry.findPositionKey(7, "BB")).isEqualTo(second);
        }
    }

    /**
     * 检查重复数字标的及资产大小写、空白规范化不新增分配；非法标的拒绝，未知合法标的仅返回空。
     */
    @Test
    void exactIdentityHitsPreserveNormalizationAndRejectInvalidMisses() {
        // 本用例 Owner 准备和查询的身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 测试标的分配出的内部稠密编号，不是永久标的 ID
        int instrumentId = identities.symbolId("1");
        // 规范化后 USDT 的内部资产编号
        int asset = identities.assetId(" usdt ");
        // 重复查询前字典版本，用于确认命中不增加分配
        long version = identities.dictionaryVersion();
        for (String /* 本次规范化或 UTF-8 哈希验证所用的原文本 */ value : new String[] {"1", new String("1"), "1", "1"}) {
            assertThat(identities.symbolId(value)).isEqualTo(instrumentId);
            assertThat(identities.findSymbolId(value)).isEqualTo(instrumentId);
        }
        for (String /* 本次规范化或 UTF-8 哈希验证所用的原文本 */ value : new String[] {"USDT", new String("USDT"), "usdt", " USDT "}) {
            assertThat(identities.assetId(value)).isEqualTo(asset);
            assertThat(identities.findAssetId(value)).isEqualTo(asset);
        }
        assertThat(identities.dictionaryVersion()).isEqualTo(version);
        for (String /* 本次规范化或 UTF-8 哈希验证所用的原文本 */ value : new String[] {null, "", "BTC/USDT"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> identities.symbolId(value))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> identities.findSymbolId(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(identities.findSymbolId("2")).isNull();
    }

    /**
     * 四个普通执行器线程各读取十万次既有键，Owner 同时扩展后续身份，验证已准备键不漂移；不启动实际 Lane，也不授予账户访问权限。
     */
    @Test
    void lanesKeepReadingPreparedKeysWhileOwnerExpandsTheDictionary() throws Exception {
        // 本用例 Owner 准备和查询的身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 并发读取前准备好的二百五十六个确定性持仓键
        long[] keys = new long[256];
        for (/* 准备、读取或扩展持仓字典的循环位置；用户按具体循环计算 */ int index = 0; index < keys.length; index++) {
            keys[index] = identities.positionKey(index + 1, "1:NET");
        }
        // 等待四个读取线程均进入任务的门闩
        var started = new java.util.concurrent.CountDownLatch(4);
        // 同时放行四个普通读取线程的门闩
        var start = new java.util.concurrent.CountDownLatch(1);
        try (/* 四个普通读取线程的执行器，资源退出时关闭 */ var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            // 读取任务的 Future 列表，逐个取结果以传播失败
            var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (/* 当前提交的读取任务序号，不是实际 Account Lane 实例 */ int lane = 0; lane < 4; lane++) {
                results.add(workers.submit(() -> {
                    started.countDown();
                    start.await();
                    for (/* 准备、读取或扩展持仓字典的循环位置；用户按具体循环计算 */ int index = 0; index < 100_000; index++) {
                        // 读取循环用低八位选中的既有键数组下标
                        int slot = index & 255;
                        // 工作线程读到或保留的持仓键，必须等于原预期键
                        long actual = identities.preparedPositionKey(slot + 1, "1:NET");
                        if (actual != keys[slot]) throw new AssertionError("prepared identity changed");
                    }
                    return null;
                }));
            }
            try {
                assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (/* 准备、读取或扩展持仓字典的循环位置；用户按具体循环计算 */ int index = 257; index < 50_000; index++) {
                identities.positionKey(index, "1:NET");
            }
            for (var /* 当前读取任务 Future，最多等待十秒取得结果 */ result : results) result.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /**
     * 检查资产、标的各首次分配增加一次版本，重复获取不增加；恢复后反向文本及本用例版本相同。
     */
    @Test
    void dictionaryVersionAdvancesOnlyWhenStableIdentitiesAreAllocatedAndSurvivesRestore() {
        // 本用例 Owner 准备和查询的身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 首次资产和标的分配前的版本
        long initial = identities.dictionaryVersion();
        // 新分配的 USDT 内部资产编号
        int assetId = identities.assetId("USDT");
        // 新分配的内部稠密标的编号
        int symbolId = identities.symbolId("1");
        identities.assetId("USDT");
        identities.symbolId("1");

        assertThat(identities.dictionaryVersion()).isEqualTo(initial + 2);
        // 从快照恢复的独立身份表，释放它不影响原表
        RuntimeIdentityRegistry restored = RuntimeIdentityRegistry.restore(identities.snapshot());
        assertThat(restored.asset(assetId)).isEqualTo("USDT");
        assertThat(restored.instrumentId(symbolId)).isEqualTo("1");
        assertThat(restored.dictionaryVersion()).isEqualTo(identities.dictionaryVersion());
    }

    /**
     * 检查回滚跳过检查点后已主动释放的分配，删掉其余后缀，保留检查点前身份并恢复顺序游标。
     */
    @Test
    void rollbackPositionKeysSkipsAllocationsAlreadyReleasedAfterCheckpoint() {
        // 本用例 Owner 准备和查询的身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        identities.positionKey(1001, "1:NET");
        // 回滚开始前保存的持仓分配顺序号
        long checkpoint = identities.positionCheckpoint();
        // 检查点后先主动释放的持仓键，后缀回滚应跳过
        long released = identities.positionKey(1002, "1:NET");
        identities.positionKey(1003, "2:NET");

        identities.releasePositionKey(released);
        identities.rollbackPositionKeys(checkpoint);

        assertThat(identities.findPositionKey(1001, "1:NET")).isNotNull();
        assertThat(identities.findPositionKey(1002, "1:NET")).isNull();
        assertThat(identities.findPositionKey(1003, "2:NET")).isNull();
        assertThat(identities.positionCheckpoint()).isEqualTo(checkpoint);
    }

    /**
     * 同一客户端键被再次准入保留后，较早终态只释放一份引用；撤销后份时才删除最后身份。
     */
    @Test
    void clientIdentityReferenceProtectsAReusedKeyFromAnOlderTerminalRelease() {
        // 本用例 Owner 准备和查询的身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 首次客户端准备结果，或第一个用户/名称的实际持仓键
        var first = identities.prepareClientKey(1001, "client-1");
        // 同客户端单号再次准入保留的实际正键
        var reused = identities.prepareClientKey(1001, "client-1");

        identities.releaseClientKey(1001, first);

        assertThat(identities.clientIdentityCount()).isOne();
        assertThat(identities.clientOrderId(1001, reused)).isEqualTo("client-1");

        identities.rollbackPreparedClientKey(1001, "client-1", reused);
        assertThat(identities.clientIdentityCount()).isZero();
    }
}
